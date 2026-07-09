package oak.server.websocket;

import oak.server.dto.PresenceEvent;
import oak.server.dto.PresenceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Mockito unit test for the presence registry. Mirrors the {@code MessageServiceTest}
 * pattern (mock {@link SimpMessagingTemplate}, verify pushes). Focuses on the
 * transition logic and, crucially, on idempotency against duplicate disconnect events.
 */
@ExtendWith(MockitoExtension.class)
class PresenceTrackerTest {

    private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private PresenceTracker tracker;

    @Test
    void multiSessionLifecyclePublishesOnlineOnFirstAndOfflineOnLast() {
        tracker.onConnect(connect("s1", "alice"));
        assertEquals(Set.of("alice"), tracker.onlineUsernames());

        tracker.onConnect(connect("s2", "alice")); // second tab — no new event
        assertEquals(Set.of("alice"), tracker.onlineUsernames());

        tracker.onDisconnect(disconnect("s1")); // one tab closed — still online
        assertEquals(Set.of("alice"), tracker.onlineUsernames());

        tracker.onDisconnect(disconnect("s2")); // last tab — offline
        assertEquals(Set.of(), tracker.onlineUsernames());

        ArgumentCaptor<PresenceEvent> events = ArgumentCaptor.forClass(PresenceEvent.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq(PresenceTracker.PRESENCE_TOPIC), events.capture());
        List<PresenceEvent> captured = events.getAllValues();
        assertEquals(2, captured.size());
        assertEquals("alice", captured.get(0).username());
        assertEquals(PresenceStatus.ONLINE, captured.get(0).status());
        assertEquals("alice", captured.get(1).username());
        assertEquals(PresenceStatus.OFFLINE, captured.get(1).status());
    }

    @Test
    void duplicateDisconnectEventIsIgnored() {
        // Spring may publish SessionDisconnectEvent more than once per session; the second
        // removal is keyed by sessionId and must be a no-op (no second OFFLINE, no exception).
        tracker.onConnect(connect("s1", "bob"));
        tracker.onDisconnect(disconnect("s1"));
        tracker.onDisconnect(disconnect("s1"));

        assertEquals(Set.of(), tracker.onlineUsernames());
        ArgumentCaptor<PresenceEvent> events = ArgumentCaptor.forClass(PresenceEvent.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq(PresenceTracker.PRESENCE_TOPIC), events.capture());
        assertEquals(PresenceStatus.ONLINE, events.getAllValues().get(0).status());
        assertEquals(PresenceStatus.OFFLINE, events.getAllValues().get(1).status());
    }

    @Test
    void duplicateConnectAndIndependentUsersTrackSeparately() {
        tracker.onConnect(connect("s1", "alice"));
        tracker.onConnect(connect("s1", "alice")); // duplicate CONNECT for the same session
        assertEquals(Set.of("alice"), tracker.onlineUsernames()); // still a single online user

        tracker.onConnect(connect("s2", "bob"));
        assertEquals(Set.of("alice", "bob"), tracker.onlineUsernames());

        // exactly two ONLINE pushes: alice once (despite the duplicate) and bob once
        verify(messagingTemplate, times(2)).convertAndSend(eq(PresenceTracker.PRESENCE_TOPIC), any(PresenceEvent.class));
    }

    // --- helpers: build STOMP session events carrying a session id + principal ---

    private static SessionConnectEvent connect(String sessionId, String username) {
        Message<byte[]> message = stompMessage(StompCommand.CONNECT, sessionId, username);
        return new SessionConnectEvent(new Object(), message);
    }

    private static SessionDisconnectEvent disconnect(String sessionId) {
        // The tracker keys disconnect handling by session id only, so the user on the
        // disconnect frame is irrelevant; "unused" mirrors that it is never read.
        Message<byte[]> message = stompMessage(StompCommand.DISCONNECT, sessionId, "unused");
        return new SessionDisconnectEvent(new Object(), message, sessionId, CloseStatus.NORMAL);
    }

    private static Message<byte[]> stompMessage(StompCommand command, String sessionId, String username) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true); // keep headers mutable so setUser() sticks (as in production)
        accessor.setSessionId(sessionId);
        accessor.setUser(new UsernamePasswordAuthenticationToken(username, null, AUTHORITIES));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
