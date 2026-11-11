package oak.server.websocket;

import lombok.extern.slf4j.Slf4j;
import oak.server.dto.PresenceActivity;
import oak.server.dto.PresenceActivityEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory registry of the roster ACTIVITY lines ("playing CS2"), the presence-class
 * sibling of {@link RosterAfkTracker}. Clients send explicit {@code /app/presence.activity}
 * reports from their own sessions (every live session of the user broadcasts the same
 * verdict, the {@code broadcastRosterAfk} pattern); the tracker keeps them per session
 * and derives the USER's activity: the first non-null report among the user's live
 * sessions, in session arrival order — a deterministic pick that matches the client's
 * "all my sessions say the same thing" broadcast.
 *
 * <p>LIVE vs PUBLISHED (the RosterAfk split): {@code liveByUsername} always carries the
 * derived truth and backs {@code GET /api/presence/activities}; {@code publishedByUsername}
 * is only the last BROADCAST, used to suppress non-news. A change inside the
 * {@link #FLIP_DAMPENING_MILLIS} window still moves the live state — the snapshot stays
 * honest — while its delta is swallowed.
 *
 * <p>The user's LAST session disconnecting silently drops the live line (presence
 * OFFLINE already told that story — a delta for a gone user would be noise); a
 * surviving-session recompute publishes when the verdict actually moved.
 */
@Component
@Slf4j
public class PresenceActivityTracker {

    /** STOMP destination activity deltas are pushed to. */
    public static final String ACTIVITY_TOPIC = "/topic/presence.activity";

    /** One published change per user per window — presence-class spam bound. */
    static final long FLIP_DAMPENING_MILLIS = 1_000;

    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    /** Insertion-ordered: the first live report of a user wins deterministically. */
    private final Map<String, Report> reportsBySession = new LinkedHashMap<>();
    /** The derived truth per user (absent = no line) — the snapshot source. */
    private final Map<String, PresenceActivity> liveByUsername = new HashMap<>();
    /** The last BROADCAST per user (absent = "subscribers assume no line"). */
    private final Map<String, PresenceActivity> publishedByUsername = new HashMap<>();
    private final Map<String, Long> lastChangeAt = new HashMap<>();
    private final Object lock = new Object();

    // Two constructors (the Clock test seam) ⇒ Spring needs the intended one marked,
    // or it hunts for a no-arg constructor and the context dies (the RosterAfk lesson).
    @Autowired
    public PresenceActivityTracker(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, Clock.systemUTC());
    }

    PresenceActivityTracker(SimpMessagingTemplate messagingTemplate, Clock clock) {
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /** A session's explicit report; {@code activity == null} clears that session's say. */
    public void setActivity(String sessionId, String username, PresenceActivity activity) {
        PresenceActivityEvent toPublish;
        synchronized (lock) {
            if (activity == null) {
                reportsBySession.remove(sessionId);
            } else {
                reportsBySession.put(sessionId, new Report(username, activity));
            }
            toPublish = applyDecision(username);
        }
        publish(toPublish);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        synchronized (lock) {
            Report report = reportsBySession.remove(sessionId); // idempotent by sessionId
            if (report == null) {
                return;
            }
            if (!hasLiveSession(report.username())) {
                liveByUsername.remove(report.username()); // silent: OFFLINE told the story
                return;
            }
            publish(applyDecision(report.username()));
        }
    }

    /** Snapshot of every user's CURRENT activity (users with a line only, never nulls). */
    public List<PresenceActivityEvent> currentActivities() {
        synchronized (lock) {
            List<PresenceActivityEvent> out = new ArrayList<>();
            for (Map.Entry<String, PresenceActivity> entry : liveByUsername.entrySet()) {
                out.add(new PresenceActivityEvent(entry.getKey(), entry.getValue()));
            }
            return out;
        }
    }

    private boolean hasLiveSession(String username) {
        for (Report report : reportsBySession.values()) {
            if (report.username().equals(username)) {
                return true;
            }
        }
        return false;
    }

    /** The user's derived activity — first non-null among live sessions, null when none. */
    private PresenceActivity decideActivity(String username) {
        for (Report report : reportsBySession.values()) {
            if (report.username().equals(username)) {
                return report.activity(); // insertion order: deterministic first pick
            }
        }
        return null;
    }

    private PresenceActivityEvent applyDecision(String username) {
        PresenceActivity activity = decideActivity(username);
        if (activity == null) {
            liveByUsername.remove(username);
        } else {
            liveByUsername.put(username, activity);
        }
        PresenceActivity told = publishedByUsername.get(username);
        boolean alreadyTold = told == null ? activity == null : told.equals(activity);
        if (alreadyTold) {
            return null; // not news — subscribers already render exactly this
        }
        Long lastChange = lastChangeAt.get(username);
        if (lastChange != null && clock.millis() - lastChange < FLIP_DAMPENING_MILLIS) {
            // Dampened: the LIVE state has already moved (the snapshot carries it);
            // only this broadcast is swallowed — the next change publishes.
            log.debug("activity change for {} dampened", username);
            return null;
        }
        lastChangeAt.put(username, clock.millis());
        publishedByUsername.put(username, activity);
        return new PresenceActivityEvent(username, activity);
    }

    private void publish(PresenceActivityEvent event) {
        if (event != null) {
            messagingTemplate.convertAndSend(ACTIVITY_TOPIC, event);
        }
    }

    private record Report(String username, PresenceActivity activity) {
    }
}
