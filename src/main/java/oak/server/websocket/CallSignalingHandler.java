package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.CallSignal;
import oak.server.service.DirectContactGate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Relays WebRTC call signaling between two users. A client sends a {@link CallSignal} to
 * {@code /app/call.signal}; the server stamps {@code from} with the authenticated sender and
 * forwards it to the target user's {@code /user/queue/call.signal}. The server never touches media.
 *
 * <p>The recipient is gated by {@link DirectContactGate} (security round 5, M1): previously
 * {@code to} was used verbatim, so any member could ring ANY user — including ones who blocked
 * them and ones frozen by RESTRICT_PRIVATE_MESSAGES — with no rate limit. Drops are silent
 * (the server does not confirm reachability to a probing client).
 *
 * <p>Note: must be {@code @Controller} (not just {@code @Component}) — Spring's STOMP
 * {@code SimpAnnotationMethodMessageHandler} scans {@code @MessageMapping} only on {@code @Controller} beans.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class CallSignalingHandler {

    /** Per-user destination relays are delivered to (clients subscribe to {@code /user/queue/call.signal}). */
    public static final String CALL_QUEUE = "/queue/call.signal";

    private final SimpMessagingTemplate messagingTemplate;
    private final DirectContactGate contactGate;

    @MessageMapping("/call.signal")
    public void relay(CallSignal signal, Principal sender) {
        if (signal == null || signal.to() == null || signal.to().isBlank()) {
            return;
        }
        boolean cardPush = "RING".equals(signal.type());
        if (!contactGate.canReach(sender.getName(), signal.to(), cardPush)) {
            log.info("[call.signal] dropped a frame from {} to {} (gated)", sender.getName(), signal.to());
            return;
        }
        CallSignal stamped = new CallSignal(
                signal.type(), sender.getName(), signal.to(), signal.sdp(), signal.candidate());
        messagingTemplate.convertAndSendToUser(signal.to(), CALL_QUEUE, stamped);
    }
}
