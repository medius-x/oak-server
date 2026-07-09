package oak.server.config;

import lombok.RequiredArgsConstructor;
import oak.server.security.AuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket for real-time push. Clients connect to {@code /ws} (carrying a
 * JWT in the {@code Authorization} header of the STOMP CONNECT, validated by
 * {@link AuthChannelInterceptor}) and subscribe to {@code /topic/channel.{id}} to
 * receive new messages in that channel.
 *
 * <p>This is the server&rarr;client push half of "variant A": messages are written via
 * REST and broadcast here. The {@code /app} prefix is reserved for future
 * client&rarr;server commands (typing indicators, WebRTC signaling).
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AuthChannelInterceptor authChannelInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
