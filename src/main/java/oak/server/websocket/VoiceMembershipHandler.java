package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.dto.VoiceCommand;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Receives voice-room join/leave signals over STOMP and hands them to {@link VoiceMembershipTracker},
 * keyed by the sender's STOMP session id so a disconnect cleans up membership automatically (no
 * reliance on a best-effort REST leave). Must be {@code @Controller} (not just {@code @Component}) —
 * Spring's STOMP {@code SimpAnnotationMethodMessageHandler} scans {@code @MessageMapping} only on
 * {@code @Controller} beans (see {@link CallSignalingHandler}).
 */
@Controller
@RequiredArgsConstructor
public class VoiceMembershipHandler {

    private final VoiceMembershipTracker tracker;

    @MessageMapping("/voice.join")
    public void join(VoiceCommand command, Principal sender, @Header("simpSessionId") String sessionId) {
        if (command == null || command.channelId() == null) {
            return;
        }
        tracker.enter(sessionId, sender.getName(), command.channelId());
    }

    @MessageMapping("/voice.leave")
    public void leave(@Header("simpSessionId") String sessionId) {
        // The channel is known by session id; the SEND body (a VoiceCommand) is intentionally ignored.
        tracker.exit(sessionId);
    }
}
