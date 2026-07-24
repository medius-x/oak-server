package oak.server.security;

import lombok.RequiredArgsConstructor;
import oak.server.entity.User;
import oak.server.repository.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates the JWT carried in the {@code Authorization} header of a STOMP CONNECT frame
 * and sets the authenticated principal on the session. A CONNECT without a valid token, or with a
 * token for a banned account, is rejected (the connection is dropped with an ERROR frame).
 */
@Component
@RequiredArgsConstructor
public class AuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }
        String token = extractToken(accessor);
        if (token == null || !tokenProvider.isValid(token)) {
            throw new MessageDeliveryException("Unauthorized: missing or invalid JWT on STOMP CONNECT");
        }
        String username = tokenProvider.extractUsername(token);
        if (isBanned(username)) {
            // Defense-in-depth: a banned member's still-valid access JWT must not open a real-time channel.
            throw new MessageDeliveryException("Unauthorized: banned account");
        }
        Authentication authentication = new UsernamePasswordAuthenticationToken(username, null, AUTHORITIES);
        accessor.setUser(authentication);
        return message;
    }

    private boolean isBanned(String username) {
        return userRepository.findByUsername(username)
                .map(User::isBanned)
                .orElse(false);
    }

    private String extractToken(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            // STOMP header names are case-insensitive per spec; some clients send lowercase
            header = accessor.getFirstNativeHeader("authorization");
        }
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
