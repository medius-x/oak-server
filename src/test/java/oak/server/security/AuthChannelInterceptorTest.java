package oak.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthChannelInterceptorTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret-0123456789";

    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(new JwtProperties(SECRET, Duration.ofHours(1)));
    private final AuthChannelInterceptor interceptor = new AuthChannelInterceptor(tokenProvider);

    @Test
    void connectWithValidBearerTokenSetsPrincipal() {
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
    void nonConnectFramesPassThroughUnchanged() {
        Message<?> subscribe = stompMessage(StompCommand.SUBSCRIBE, Map.of());
        assertDoesNotThrow(() -> interceptor.preSend(subscribe, null));
    }

    private static Message<?> stompMessage(StompCommand command, Map<String, String> headers) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true); // keep headers mutable so preSend can setUser()
        headers.forEach(accessor::addNativeHeader);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
