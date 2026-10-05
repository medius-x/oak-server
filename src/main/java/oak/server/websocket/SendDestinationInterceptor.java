package oak.server.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Closes the broker-injection hole. The simple broker does not authorize {@code SEND}
 * frames by origin of destination: without this interceptor a client could publish a
 * frame with destination {@code /topic/channel.5} (or {@code /user/<victim>/queue/...})
 * and the broker would fan it out to every subscriber exactly as if the server had sent
 * it. That handed any authenticated member a whole family of spoofing primitives:
 * fake {@code MessageResponse} bodies rendered in channels the attacker cannot even read
 * (with an {@code authorUsername} of the attacker's choosing), forged
 * {@code call.signal RING}/{@code member.kick}/{@code voice.control KICKED} pushes to
 * arbitrary per-user queues, spoofed presence/voice-state, and unthrottled server-side
 * fan-out amplification.
 *
 * <p>Clients have no legitimate reason to publish anywhere under {@code /topic} or
 * {@code /queue}: every client&rarr;server command is a {@code @MessageMapping} under the
 * {@code /app} prefix (see {@code WebSocketConfig}); per-user {@code /user/queue/...}
 * destinations are written exclusively by the server via {@code convertAndSendToUser}.
 * So a {@code SEND} whose destination is not {@code /app/...} is dropped
 * ({@code null}) with a WARN — the broker never sees it. Other commands (CONNECT is
 * authenticated by {@code AuthChannelInterceptor}, SUBSCRIBE is gated by
 * {@code ChannelSubscriptionInterceptor}) pass through unchanged.
 */
@Component
@Slf4j
public class SendDestinationInterceptor implements ChannelInterceptor {

    static final String APPLICATION_PREFIX = "/app/";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SEND.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination != null && destination.startsWith(APPLICATION_PREFIX)) {
            return message;
        }
        String username = accessor.getUser() == null ? "anonymous" : accessor.getUser().getName();
        log.warn("dropped SEND to non-app destination '{}' from '{}' — clients may only send to {}**",
                destination, username, APPLICATION_PREFIX);
        return null;
    }
}
