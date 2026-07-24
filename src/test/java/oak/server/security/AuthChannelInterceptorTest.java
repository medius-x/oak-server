package oak.server.security;

import oak.server.entity.User;
import oak.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthChannelInterceptorTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret-0123456789";

    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(new JwtProperties(SECRET, Duration.ofHours(1), Duration.ofDays(30)));
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthChannelInterceptor interceptor = new AuthChannelInterceptor(tokenProvider, userRepository);

    @Test
    void connectWithValidBearerTokenSetsPrincipal() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User("alice", "hash")));
        String token = tokenProvider.generateToken("alice");
        Message<?> result = interceptor.preSend(stompMessage(StompCommand.CONNECT,
                Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token)), null);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertNotNull(accessor.getUser());
        assertEquals("alice", accessor.getUser().getName());
    }

    @Test
    void connectWithoutAuthorizationHeaderIsRejected() {
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(stompMessage(StompCommand.CONNECT, Map.of()), null));
    }

    @Test
    void connectWithInvalidTokenIsRejected() {
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(stompMessage(StompCommand.CONNECT,
                        Map.of(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")), null));
    }

    @Test
    void connectWithBannedAccountIsRejected() {
        User banned = new User("alice", "hash");
        banned.setBanned(true);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(banned));
        String token = tokenProvider.generateToken("alice");
        assertThrows(MessageDeliveryException.class,
                () -> interceptor.preSend(stompMessage(StompCommand.CONNECT,
                        Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token)), null));
    }

    @Test
    void nonConnectFramesPassThroughUnchanged() {
        Message<?> subscribe = stompMessage(StompCommand.SUBSCRIBE, Map.of());
        assertDoesNotThrow(() -> interceptor.preSend(subscribe, null));
    }

    @Test
    void connectWithLowercaseAuthorizationHeaderIsAccepted() {
        // Mirrors what the oak-client StompClient sends (STOMP convention: lowercase header names).
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User("alice", "hash")));
        String token = tokenProvider.generateToken("alice");
        Message<?> result = interceptor.preSend(stompMessage(StompCommand.CONNECT,
                Map.of("authorization", "Bearer " + token)), null);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertNotNull(accessor.getUser());
        assertEquals("alice", accessor.getUser().getName());
    }

    private static Message<?> stompMessage(StompCommand command, Map<String, String> headers) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true); // keep headers mutable so preSend can setUser()
        headers.forEach(accessor::addNativeHeader);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
