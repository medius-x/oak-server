package oak.server.websocket;

import oak.server.dto.PresenceEvent;
import oak.server.dto.PresenceStatus;
import oak.server.dto.UserCameOnlineEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory registry of who is currently connected over STOMP, fed by Spring's
 * {@link SessionConnectEvent}/{@link SessionDisconnectEvent}. The authenticated
 * principal (username) is attached to each session by {@code AuthChannelInterceptor},
 * so {@code accessor.getUser().getName()} is the username.
 *
 * <p>A user may hold several sessions at once (multiple tabs); they are "online" while
 * at least one session is open. Only the 0&rarr;1 and 1&rarr;0 transitions push a
 * {@link PresenceEvent} to {@link #PRESENCE_TOPIC}.
 *
 * <p><b>Invisible</b> (the status menu, user request 2026-11-17): a masked user LOOKS
 * offline to everyone — the mask filters them out of {@link #onlineUsernames()} and the
 * ONLINE announcement flips to OFFLINE (and back on unmask), exactly as if their last
 * session had dropped. No viewer — owner, admin, nobody — sees through the mask
 * (user decision, Discord parity); the sessions themselves stay alive, so calls and
 * invites still reach them and their voice-room membership keeps rendering honestly.
 *
 * <p><b>Idempotency:</b> Spring may publish {@link SessionDisconnectEvent} more than
 * once per session, and a CONNECT could theoretically repeat too. Removal/guarding is
 * therefore keyed by {@code sessionId}: a second removal for the same session is a
 * no-op, so duplicate events can never under-count. Boundary decisions are made under
 * a single lock, which also eliminates any online/offline ordering race when one user
 * reconnects (close + open concurrently).
 *
 * <p>Publishing happens outside the lock so broker dispatch never blocks the registry.
 */
@Component
public class PresenceTracker {

    /** STOMP destination presence deltas are pushed to: {@code /topic/presence}. */
    public static final String PRESENCE_TOPIC = "/topic/presence";

    private final SimpMessagingTemplate messagingTemplate;
    /** Fires {@link UserCameOnlineEvent} on a user's real visit (nullable — the
     *  one-argument constructor used by plain unit tests). */
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public PresenceTracker(SimpMessagingTemplate messagingTemplate, ApplicationEventPublisher eventPublisher) {
        this.messagingTemplate = messagingTemplate;
        this.eventPublisher = eventPublisher;
    }

    PresenceTracker(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, null);
    }

    private final Map<String, String> sessionToUsername = new HashMap<>();
    private final Map<String, Integer> usernameCount = new HashMap<>();
    /** Users whose clients asked to LOOK offline while their sessions stay open. */
    private final Set<String> maskedUsernames = new HashSet<>();
    private final Object lock = new Object();

    @EventListener
    public void onConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        if (principal == null || principal.getName() == null) {
            return;
        }
        String username = principal.getName();
        String sessionId = accessor.getSessionId();

        PresenceEvent toPublish;
        boolean cameOnline;
        synchronized (lock) {
            // A duplicate CONNECT for the same session must not double-count.
            if (sessionToUsername.putIfAbsent(sessionId, username) != null) {
                return;
            }
            int openSessions = usernameCount.merge(username, 1, Integer::sum);
            cameOnline = openSessions == 1;
            if (cameOnline && eventPublisher != null) {
                // The LAST SEEN bookkeeping (V22): a real visit, INVISIBLE mask
                // notwithstanding — it hides the user from the world, not this fact.
                eventPublisher.publishEvent(new UserCameOnlineEvent(username));
            }
            if (!cameOnline || maskedUsernames.contains(username)) {
                return; // already online through another session — or hidden on purpose
            }
            toPublish = new PresenceEvent(username, PresenceStatus.ONLINE);
        }
        publish(toPublish);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        PresenceEvent toPublish;
        synchronized (lock) {
            // remove returns null on a duplicate disconnect → no-op (idempotent).
            String username = sessionToUsername.remove(sessionId);
            if (username == null) {
                return;
            }
            int openSessions = usernameCount.merge(username, -1, Integer::sum);
            if (openSessions > 0) {
                return; // other sessions of this user are still open
            }
            usernameCount.remove(username);
            maskedUsernames.remove(username); // the mask dies with the last session
            toPublish = new PresenceEvent(username, PresenceStatus.OFFLINE);
        }
        publish(toPublish);
    }

    /** Whether the user has at least one live STOMP session (their real liveness —
     *  the INVISIBLE mask does not change it, it only hides the user from the
     *  world). The game-session sweeper asks this: a killed client's sessions
     *  close, an invisible-but-alive user's do not. */
    public boolean isOnline(String username) {
        synchronized (lock) {
            return usernameCount.containsKey(username);
        }
    }

    /**
     * The INVISIBLE mask (the status menu): flip whether the user's live sessions
     * count as online for the world. Flipping ON while connected publishes OFFLINE
     * (the world sees them leave); flipping OFF publishes ONLINE again. The user's
     * own status frames keep flowing — only this dimension is masked.
     */
    public void setMasked(String username, boolean masked) {
        PresenceEvent toPublish;
        synchronized (lock) {
            if (masked == maskedUsernames.contains(username)) {
                return;
            }
            boolean connected = usernameCount.containsKey(username);
            if (masked) {
                maskedUsernames.add(username);
                if (!connected) {
                    return; // not online — nothing to announce
                }
                toPublish = new PresenceEvent(username, PresenceStatus.OFFLINE);
            } else {
                maskedUsernames.remove(username);
                if (!connected) {
                    return;
                }
                toPublish = new PresenceEvent(username, PresenceStatus.ONLINE);
            }
        }
        publish(toPublish);
    }

    /** Snapshot of currently-online usernames (masked users excluded), used to seed
     *  {@code GET /api/presence}. */
    public Set<String> onlineUsernames() {
        synchronized (lock) {
            Set<String> visible = new HashSet<>(usernameCount.keySet());
            visible.removeAll(maskedUsernames);
            return Set.copyOf(visible);
        }
    }

    private void publish(PresenceEvent event) {
        messagingTemplate.convertAndSend(PRESENCE_TOPIC, event);
    }
}
