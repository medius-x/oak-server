package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.dto.VoiceCommand;
import oak.server.entity.Channel;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.service.AuthorizationService;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Optional;

/**
 * Receives voice-room join/leave signals over STOMP and hands them to {@link VoiceMembershipTracker},
 * keyed by the sender's STOMP session id so a disconnect cleans up membership automatically (no
 * reliance on a best-effort REST leave). Joining requires {@link Permission#CONNECT} (channel-scoped);
 * a denied join is a silent no-op — the REST {@code /room} gate is the primary check, this is the
 * STOMP-side defense. Must be {@code @Controller} (not just {@code @Component}) — Spring's STOMP
 * {@code SimpAnnotationMethodMessageHandler} scans {@code @MessageMapping} only on {@code @Controller}
 * beans (see {@link CallSignalingHandler}).
 */
@Controller
@RequiredArgsConstructor
public class VoiceMembershipHandler {

    private final VoiceMembershipTracker tracker;
    private final AuthorizationService auth;
    private final ChannelRepository channelRepository;

    @MessageMapping("/voice.join")
    public void join(VoiceCommand command, Principal sender, @Header("simpSessionId") String sessionId) {
        if (command == null || command.channelId() == null) {
            return;
        }
        // CONNECT gate (defense-in-depth beside the REST /room check). Denied → don't enter the room.
        Optional<Channel> channel = channelRepository.findById(command.channelId());
        if (channel.isEmpty()) {
            return;
        }
        Optional<User> actor = auth.findUser(sender.getName());
        if (actor.isEmpty() || !auth.hasInChannel(actor.get(), channel.get(), Permission.CONNECT)) {
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
