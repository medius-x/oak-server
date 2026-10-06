package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Connection-quality echo for the client's ping indicator (Discord-style RTT over the
 * long-lived gateway connection). A client sends its own epoch-millis timestamp to
 * {@code /app/ping}; the server echoes the value verbatim back to the SAME user on
 * {@code /user/queue/ping}. RTT is computed client-side as {@code now - echoed value},
 * so the server does no timing of its own and adds no clock assumptions.
 *
 * <p>The payload is a bare JSON number (the {@code Long}-timestamp-body precedent of
 * {@code /topic/channels.changed}); no DTO is needed until the protocol grows one.
 * An old server without this mapping silently drops the SEND — the client MUST read
 * "no echo" as {@code unknown}, never as an error.
 *
 * <p>Security: the recipient is always the authenticated sender (no cross-user reach,
 * nothing to enumerate), and one inbound frame produces exactly one outbound frame on
 * the attacker's own connection — no fan-out, so no rate limit is warranted. Must be
 * {@code @Controller} — {@code @MessageMapping} is scanned only on {@code @Controller} beans.
 */
@Controller
@RequiredArgsConstructor
public class PingHandler {

    /** Per-user destination the echo is delivered to (clients subscribe to {@code /user/queue/ping}). */
    public static final String PING_QUEUE = "/queue/ping";

    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/ping")
    public void ping(Long clientSentMillis, Principal sender) {
        if (sender == null || clientSentMillis == null) {
            return;
        }
        messagingTemplate.convertAndSendToUser(sender.getName(), PING_QUEUE, clientSentMillis);
    }
}
