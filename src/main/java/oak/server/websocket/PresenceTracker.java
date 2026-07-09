package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.dto.PresenceEvent;
import oak.server.dto.PresenceStatus;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.HashMap;
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
@RequiredArgsConstructor
public class PresenceTracker {

    /** STOMP destination presence deltas are pushed to: {@code /topic/presence}. */
    public static final String PRESENCE_TOPIC = "/topic/presence";

    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, String> sessionToUsername = new HashMap<>();
    private final Map<String, Integer> usernameCount = new HashMap<>();
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
        synchronized (lock) {
            // A duplicate CONNECT for the same session must not double-count.
            if (sessionToUsername.putIfAbsent(sessionId, username) != null) {
                return;
            }
            int openSessions = usernameCount.merge(username, 1, Integer::sum);
            if (openSessions != 1) {
                return; // the user already had another session online
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
            toPublish = new PresenceEvent(username, PresenceStatus.OFFLINE);
        }
        publish(toPublish);
    }

    /** Snapshot of currently-online usernames, used to seed {@code GET /api/presence}. */
    public Set<String> onlineUsernames() {
        synchronized (lock) {
            return Set.copyOf(usernameCount.keySet());
        }
    }

    private void publish(PresenceEvent event) {
        messagingTemplate.convertAndSend(PRESENCE_TOPIC, event);
    }
}
