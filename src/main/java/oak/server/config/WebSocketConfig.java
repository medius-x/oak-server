package oak.server.config;

import lombok.RequiredArgsConstructor;
import oak.server.security.AuthChannelInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
 *
 * <p>STOMP heartbeats ({@link #HEARTBEAT_MS}) are enabled on the simple broker so the server
 * detects and closes stale sessions (a client that vanished without a DISCONNECT). That fires
 * {@code SessionDisconnectEvent} and lets {@code PresenceTracker} publish OFFLINE promptly,
 * instead of waiting on a TCP keepalive timeout.
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(TurnProperties.class)
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AuthChannelInterceptor authChannelInterceptor;

    /** STOMP heartbeat interval (ms) in both directions (server can-send / wants-receive). */
    static final long HEARTBEAT_MS = 10_000;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Heartbeats require a TaskScheduler; created inline (not a bean) so the broker owns it
        // without extra wiring. Daemon threads so they never block JVM shutdown.
        ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.setPoolSize(2);
        heartbeatScheduler.setThreadNamePrefix("oak-stomp-hb-");
        heartbeatScheduler.setDaemon(true);
        heartbeatScheduler.initialize();

        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler);
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
