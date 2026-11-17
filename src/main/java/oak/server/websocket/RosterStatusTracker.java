package oak.server.websocket;

import lombok.extern.slf4j.Slf4j;
import oak.server.dto.PresenceStatusEvent;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The roster's MANUAL presence dimension (the status menu, user request 2026-11-17 —
 * the successor of the boolean AFK tracker of 2026-11-04): a member's status is what
 * THEIR CLIENT says it is — {@code online}, {@code away} (idle at the computer) or
 * {@code dnd} (the auto-mode paints dnd while the user sits in any voice room or
 * 1:1 call; a manual pick pins the status and switches the auto-detect off — all
 * of that deciding happens client-side). This is a SEPARATE entity from the voice
 * AFK sweep ({@link VoiceAfkSweeper}); {@code offline} is NOT a status here at all
 * — it is what {@code /topic/presence} tells when every session of the user is
 * gone, and it can never be reported by hand.
 *
 * <p>Reports are per STOMP session; the user's aggregate keeps the 0.5.x boolean
 * semantics for the away half (an ONLINE report from ANY device cancels another
 * device's idle-away — the user is demonstrably at the computer) with
 * {@code dnd} layered on top as un-outvotable (a manual pick on one device stays).
 * When a session disconnects its report is dropped — with every session gone the
 * user is offline and {@code /topic/presence} tells that story, so the status is
 * silently removed (no frame); with other sessions left, the aggregate is
 * re-decided and a genuine flip is published.
 *
 * <p>Only TRANSITIONS are published (one frame per flip), on TWO wires at once:
 * the status frame {@link PresenceStatusEvent} on {@link #TOPIC} for new clients,
 * and the LEGACY {@link VoiceAfkStatusEvent} on {@link #LEGACY_AFK_TOPIC} — the
 * 0.5.x clients keep rendering the yellow dot from it unchanged (every non-away
 * status maps to {@code afk=false}; a dnd member reads present-green there, an
 * accepted degradation until they update). A per-user dampening window bounds a
 * flapping client; a withheld frame is caught up by the next flip outside the
 * window (fresh subscribers read the live set via {@link #currentStatuses()}).
 *
 * <p><b>Idempotency:</b> {@link SessionDisconnectEvent} may fire more than once per
 * session; removal is keyed by {@code sessionId}, a duplicate is a no-op (the
 * {@link PresenceTracker} lesson). Boundary decisions happen under one lock;
 * publishing happens OUTSIDE it so broker dispatch never blocks the registry.
 */
@Component
@Slf4j
public class RosterStatusTracker {

    /** STOMP destination status transitions are pushed to (the 2026-11-17 wire). */
    public static final String TOPIC = "/topic/roster.status";
    /** The 0.5.x AFK wire, still published for old clients (kept from 2026-11-04). */
    public static final String LEGACY_AFK_TOPIC = "/topic/voice.afk";
    /** Anti-flap: at most one published flip per user per window. */
    static final long FLIP_DAMPENING_MILLIS = 2_000;

    /** The presence statuses a client may report about its own user. */
    public static final String ONLINE = "online";
    public static final String AWAY = "away";
    public static final String DND = "dnd";

    /** One client's verdict about its own user, keyed by the STOMP session it rode in on.
     *  {@code invisible} is the user's request to LOOK offline to everyone while the
     *  sessions stay alive (the status menu) — it rides the same report so one wire
     *  carries the whole presence decision. */
    private record Report(String username, String status, boolean invisible) {
    }

    private final SimpMessagingTemplate messagingTemplate;
    private final PresenceTracker presenceTracker;
    private final Clock clock;
    private final CatchUpScheduler catchUpScheduler;
    /** One pending catch-up per user — a re-withheld flip replaces nothing (the
     *  already-scheduled task re-reads the live aggregate when it fires). */
    private final Set<String> catchUpScheduled = new HashSet<>();

    private final Map<String, Report> reportsBySession = new HashMap<>();
    /** The decided live aggregate — the non-online users only, everyone else is present. */
    private final Map<String, String> statusByUsername = new HashMap<>();
    /** The decided live INVISIBLE aggregate — setMasked fires only on its EDGES. */
    private final Set<String> maskedUsernames = new HashSet<>();
    /** What subscribers have last been told (absent == online, the wire's default). */
    private final Map<String, String> publishedByUsername = new HashMap<>();
    private final Map<String, Long> lastFlipAt = new HashMap<>();
    private final Object lock = new Object();

    /** Two constructors exist (the second is the test seam), so Spring must be told
     *  which one to build the bean through — without the annotation it hunts for a
     *  no-arg constructor and the context dies. */
    @Autowired
    public RosterStatusTracker(SimpMessagingTemplate messagingTemplate, PresenceTracker presenceTracker) {
        this(messagingTemplate, presenceTracker, Clock.systemUTC(), (task, delayMillis) -> CATCH_UP_THREAD.schedule(task, delayMillis, TimeUnit.MILLISECONDS));
    }

    /** Test seam: a controllable clock and catch-up scheduler drive the dampening
     *  window deterministically. */
    RosterStatusTracker(SimpMessagingTemplate messagingTemplate, PresenceTracker presenceTracker, Clock clock, CatchUpScheduler catchUpScheduler) {
        this.messagingTemplate = messagingTemplate;
        this.presenceTracker = presenceTracker;
        this.clock = clock;
        this.catchUpScheduler = catchUpScheduler;
    }

    /** The production catch-up lane: one daemon thread is plenty (presence flips
     *  are rare; the task only re-reads the registry). */
    private static final ScheduledExecutorService CATCH_UP_THREAD =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "roster-status-catchup");
                thread.setDaemon(true);
                return thread;
            });

    /** Applies one client status report and publishes the user's flip if the aggregate changed. */
    public void setStatus(String sessionId, String username, String status) {
        setStatus(sessionId, username, status, false);
    }

    /** The full report — the status plus the INVISIBLE mask request. */
    public void setStatus(String sessionId, String username, String status, boolean invisible) {
        Outcome outcome;
        synchronized (lock) {
            reportsBySession.put(sessionId, new Report(username, status, invisible));
            outcome = applyDecision(username);
        }
        publish(outcome);
    }

    /** The 0.5.x boolean bridge ({@code /app/roster.afk}): away maps to {@code away},
     *  present to {@code online} — old clients keep working against the new server. */
    public void setAway(String sessionId, String username, boolean away) {
        setStatus(sessionId, username, away ? AWAY : ONLINE, false);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        Outcome outcome = null;
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
                // roster grays the dot and drops the flag) — no status frame needed.
                statusByUsername.remove(username);
                publishedByUsername.remove(username);
                lastFlipAt.remove(username);
                maskedUsernames.remove(username); // the mask dies with the sessions too
                return;
            }
            outcome = applyDecision(username);
        }
        publish(outcome);
    }

    /** Everything one decision may emit — any part may be null when that wire has
     *  nothing new to tell; {@code maskTo} is null while the mask is unchanged;
     *  {@code dampened} marks a REAL flip the anti-flap window withheld (a catch-up
     *  frame then follows after the window — a short call's dnd→online must not
     *  stay unsaid until the user's next flip hours later, live report 2026-11-17). */
    private record Outcome(PresenceStatusEvent status, VoiceAfkStatusEvent legacyAfk, String username, Boolean maskTo, boolean dampened) {
    }

    /** The catch-up scheduler seam — production uses a single daemon thread. */
    @FunctionalInterface
    public interface CatchUpScheduler {
        void schedule(Runnable task, long delayMillis);
    }

    /**
     * Recomputes the user's aggregate against their live reports and returns the frames
     * to broadcast (plus the INVISIBLE mask flip, when the aggregate changed one).
     * CALLER HOLDS THE LOCK; everything returned is acted on outside it.
     */
    private Outcome applyDecision(String username) {
        boolean invisible = decideInvisible(username);
        Boolean maskFlip = null;
        if (invisible) {
            if (maskedUsernames.add(username)) {
                maskFlip = true;
            }
        } else if (maskedUsernames.remove(username)) {
            maskFlip = false;
            // A user coming OUT of the mask re-introduces themselves from scratch:
            // the world last heard OFFLINE, so whatever status they now hold is news.
            publishedByUsername.remove(username);
        }
        if (invisible) {
            // Masked: the world sees OFFLINE (PresenceTracker tells it) — no status
            // frames of a hidden user ever leave this server, and the live sets drop
            // them so a fresh subscriber's seed matches what the deltas said.
            statusByUsername.remove(username);
            publishedByUsername.remove(username);
            lastFlipAt.remove(username);
            return new Outcome(null, null, username, maskFlip, false);
        }
        String status = decideStatus(username);
        if (status.equals(ONLINE)) {
            statusByUsername.remove(username);
        } else {
            statusByUsername.put(username, status);
        }
        String told = publishedByUsername.get(username);
        boolean away = status.equals(AWAY);
        boolean toldAway = AWAY.equals(told);
        if (status.equals(told == null ? ONLINE : told)) {
            // Nothing new: an absent entry means "subscribers assume online", so the
            // first ONLINE verdict is not news either — only the flips are.
            return new Outcome(null, null, username, maskFlip, false);
        }
        long now = clock.millis();
        Long lastFlip = lastFlipAt.get(username);
        if (lastFlip != null && now - lastFlip < FLIP_DAMPENING_MILLIS) {
            log.debug("[roster-status] flip of {} dampened ({} ms after the previous frame) — a catch-up frame follows", username, now - lastFlip);
            // State applied, frame withheld FOR NOW: the dampening is a RATE LIMIT,
            // never a loss — a scheduled catch-up re-tells the live aggregate right
            // after the window (short calls flip dnd→online inside it, live 2026-11-17).
            return new Outcome(null, null, username, maskFlip, true);
        }
        lastFlipAt.put(username, now);
        publishedByUsername.put(username, status);
        // The legacy AFK wire only cares about the away-EDGE of the story.
        VoiceAfkStatusEvent legacy = toldAway == away ? null : new VoiceAfkStatusEvent(username, away);
        return new Outcome(new PresenceStatusEvent(username, status), legacy, username, maskFlip, false);
    }

    /** Invisible when ANY live report asks for it — the private pick is not outvoted
     *  by another device any more than a manual dnd is. */
    private boolean decideInvisible(String username) {
        for (Report report : reportsBySession.values()) {
            if (report.username().equals(username) && report.invisible()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The aggregate over all of the user's live reports. {@code dnd} cannot be outvoted
     * (a manual pick on one device stays); below that the 0.5.x boolean semantics carry
     * over — an ONLINE report from ANY device means the user is demonstrably at the
     * computer and cancels another device's idle-away; {@code away} only when every
     * live report says away; no reports at all → online (the caller drops the flag).
     */
    private String decideStatus(String username) {
        boolean anyAway = false;
        boolean anyOnline = false;
        for (Report report : reportsBySession.values()) {
            if (report.username().equals(username)) {
                if (DND.equals(report.status())) {
                    return DND;
                }
                if (ONLINE.equals(report.status())) {
                    anyOnline = true;
                }
                if (AWAY.equals(report.status())) {
                    anyAway = true;
                }
            }
        }
        return anyOnline || !anyAway ? ONLINE : AWAY;
    }

    /** Snapshot of the non-online statuses — the pull side of {@code GET /api/presence/status}.
     *  Masked (invisible) users never appear: to the world they are simply offline. */
    public Map<String, String> currentStatuses() {
        synchronized (lock) {
            Map<String, String> visible = new HashMap<>();
            for (Map.Entry<String, String> entry : statusByUsername.entrySet()) {
                if (!decideInvisible(entry.getKey())) {
                    visible.put(entry.getKey(), entry.getValue());
                }
            }
            return Map.copyOf(visible);
        }
    }

    /** The legacy pull side ({@code GET /api/voice/afk}): the away set alone. */
    public Set<String> currentAfk() {
        synchronized (lock) {
            Set<String> away = new HashSet<>();
            for (Map.Entry<String, String> entry : statusByUsername.entrySet()) {
                if (AWAY.equals(entry.getValue())) {
                    away.add(entry.getKey());
                }
            }
            return Set.copyOf(away);
        }
    }

    private void publish(Outcome outcome) {
        if (outcome == null) {
            return;
        }
        if (outcome.status() != null) {
            messagingTemplate.convertAndSend(TOPIC, outcome.status());
        }
        if (outcome.legacyAfk() != null) {
            messagingTemplate.convertAndSend(LEGACY_AFK_TOPIC, outcome.legacyAfk());
        }
        if (outcome.maskTo() != null) {
            presenceTracker.setMasked(outcome.username(), outcome.maskTo());
        }
        if (outcome.dampened()) {
            scheduleCatchUp(outcome.username());
        }
    }

    /** One in-flight catch-up per user: the task re-reads the LIVE aggregate when
     *  it fires, so a later flip inside the window needs no task of its own. */
    private void scheduleCatchUp(String username) {
        synchronized (lock) {
            if (!catchUpScheduled.add(username)) {
                return;
            }
        }
        catchUpScheduler.schedule(() -> {
            Outcome outcome;
            synchronized (lock) {
                catchUpScheduled.remove(username);
                if (!reportsBySession.values().stream().anyMatch(report -> report.username().equals(username))) {
                    return; // the user left while we waited — presence tells that story
                }
                outcome = applyDecision(username);
                if (outcome.dampened()) {
                    // The window is still open (a flapping client) — re-arm; the rate
                    // limit still holds: at most one frame per window per user.
                    scheduleCatchUp(username);
                }
            }
            publish(outcome);
        }, FLIP_DAMPENING_MILLIS);
    }
}
