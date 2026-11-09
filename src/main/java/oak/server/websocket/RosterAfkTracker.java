package oak.server.websocket;

import lombok.extern.slf4j.Slf4j;
import oak.server.dto.VoiceAfkStatusEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The roster's AFK presence dimension (user request 2026-11-04, reworked the same day):
 * a member is "away" when THEIR CLIENT says so — the decision lives client-side (system-wide
 * input idle; speaking in voice or streaming keeps the member present) and arrives here as
 * explicit {@code /app/roster.afk} transitions. This is a SEPARATE entity from the voice AFK
 * sweep ({@link VoiceAfkSweeper} + the server-profile timeout): sitting in no voice room
 * never blocks the yellow dot, and the sweep's timeout has no say here.
 *
 * <p>Reports are per STOMP session; a user is away when they hold at least one report and
 * ALL of their reports say away (multi-device: active on any one device = present). When a
 * session disconnects its report is dropped — with every session gone the user is offline
 * and {@code /topic/presence} tells that story, so the away flag is silently removed (no
 * {@code afk=false} frame); with other sessions left, the aggregate is re-decided and a
 * genuine flip is published.
 *
 * <p>Only TRANSITIONS are published (one frame per flip) to {@link #TOPIC} — the wire name
 * is inherited from the 0.5.0 voice-scoped implementation so 0.5.x clients keep rendering
 * the events unchanged. A per-user dampening window ({@link #FLIP_DAMPENING_MILLIS})
 * bounds a flapping client to at most one broadcast frame per window — presence-class
 * spam, same trust level as {@code /topic/presence}; a withheld frame is caught up by the
 * next flip outside the window (the live set always reflects the true state, fresh
 * subscribers read it via {@link #currentAfk()}).
 *
 * <p><b>Idempotency:</b> {@link SessionDisconnectEvent} may fire more than once per session;
 * removal is keyed by {@code sessionId}, a duplicate is a no-op (the {@link PresenceTracker}
 * lesson). Boundary decisions happen under one lock; publishing happens OUTSIDE it so broker
 * dispatch never blocks the registry.
 */
@Component
@Slf4j
public class RosterAfkTracker {

    /** STOMP destination AFK transitions are pushed to (kept from the 0.5.0 wire). */
    public static final String TOPIC = "/topic/voice.afk";
    /** Anti-flap: at most one published flip per user per window. */
    static final long FLIP_DAMPENING_MILLIS = 2_000;

    /** One client's verdict about its own user, keyed by the STOMP session it rode in on. */
    private record Report(String username, boolean away) {
    }

    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    private final Map<String, Report> reportsBySession = new HashMap<>();
    /** The decided live aggregate — only the away users, everyone else is present. */
    private final Set<String> awayUsernames = new HashSet<>();
    /** What subscribers have last been told (absent == present, the wire's default). */
    private final Map<String, Boolean> publishedByUsername = new HashMap<>();
    private final Map<String, Long> lastFlipAt = new HashMap<>();
    private final Object lock = new Object();

    /** Two constructors exist (the second is the test seam), so Spring must be told
     *  which one to build the bean through — without the annotation it hunts for a
     *  no-arg constructor and the context dies. */
    @Autowired
    public RosterAfkTracker(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, Clock.systemUTC());
    }

    /** Test seam: a controllable clock drives the dampening window deterministically. */
    RosterAfkTracker(SimpMessagingTemplate messagingTemplate, Clock clock) {
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /** Applies one client report and publishes the user's flip if the aggregate changed. */
    public void setAway(String sessionId, String username, boolean away) {
        VoiceAfkStatusEvent toPublish;
        synchronized (lock) {
            reportsBySession.put(sessionId, new Report(username, away));
            toPublish = applyDecision(username);
        }
        publish(toPublish);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        VoiceAfkStatusEvent toPublish = null;
        synchronized (lock) {
            Report report = reportsBySession.remove(sessionId);
            if (report == null) {
                return; // duplicate disconnect — idempotent no-op
            }
            String username = report.username();
            boolean stillReported = reportsBySession.values().stream()
                    .anyMatch(other -> other.username().equals(username));
            if (!stillReported) {
                // Every session gone: presence OFFLINE carries the story (the client's
                // roster grays the dot and drops the flag) — no afk frame needed.
                awayUsernames.remove(username);
                publishedByUsername.remove(username);
                lastFlipAt.remove(username);
                return;
            }
            toPublish = applyDecision(username);
        }
        publish(toPublish);
    }

    /**
     * Recomputes the user's aggregate against their live reports and returns the frame to
     * broadcast, or {@code null} when there is nothing new to tell. CALLER HOLDS THE LOCK;
     * the returned frame is published outside it.
     */
    private VoiceAfkStatusEvent applyDecision(String username) {
        boolean away = decideAway(username);
        if (away) {
            awayUsernames.add(username);
        } else {
            awayUsernames.remove(username);
        }
        Boolean told = publishedByUsername.get(username);
        if (told == null ? !away : told == away) {
            // Nothing new: an absent entry means "subscribers assume present", so the
            // first PRESENT verdict is not news either — only the flips are.
            return null;
        }
        long now = clock.millis();
        Long lastFlip = lastFlipAt.get(username);
        if (lastFlip != null && now - lastFlip < FLIP_DAMPENING_MILLIS) {
            log.debug("[roster-afk] flip of {} dampened ({} ms after the previous frame)", username, now - lastFlip);
            return null; // state applied, frame withheld — the next flip catches up
        }
        lastFlipAt.put(username, now);
        publishedByUsername.put(username, away);
        return new VoiceAfkStatusEvent(username, away);
    }

    /** Away ⇔ at least one live report and every live report says away. */
    private boolean decideAway(String username) {
        boolean any = false;
        for (Report report : reportsBySession.values()) {
            if (report.username().equals(username)) {
                any = true;
                if (!report.away()) {
                    return false; // present on this device → present, short-circuit
                }
            }
        }
        return any;
    }

    /** Snapshot of the currently-away usernames — the pull side of {@code GET /api/voice/afk}. */
    public Set<String> currentAfk() {
        synchronized (lock) {
            return Set.copyOf(awayUsernames);
        }
    }

    private void publish(VoiceAfkStatusEvent event) {
        if (event != null) {
            messagingTemplate.convertAndSend(TOPIC, event);
        }
    }
}
