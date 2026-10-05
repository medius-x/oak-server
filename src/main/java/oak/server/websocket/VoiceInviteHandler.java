package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.VoiceInvite;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.service.AuthorizationService;
import oak.server.service.DirectContactGate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Optional;

/**
 * Relays voice-channel invitations ({@link VoiceInviteHandler#relay}): the sender names
 * a channel and a recipient; the server stamps {@code from}, resolves the channel's
 * display name and delivers to {@code /user/queue/voice.invite}. Anything off — a
 * non-VOICE channel, a sender without VIEW_CHANNEL on it, a blank target, an unreachable
 * recipient ({@link DirectContactGate}, security round 5: blocks, DM restriction, rate) —
 * is a SILENT no-op (the call.signal model: a probing client learns nothing from an error).
 *
 * <p>Must be {@code @Controller} (not just {@code @Component}) — Spring's STOMP
 * {@code SimpAnnotationMethodMessageHandler} scans {@code @MessageMapping} only on
 * {@code @Controller} beans (see {@code CallSignalingHandler}).
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class VoiceInviteHandler {

    /** The per-user queue clients subscribe to ({@code /user/queue/voice.invite}). */
    public static final String VOICE_INVITE_QUEUE = "/queue/voice.invite";

    private final SimpMessagingTemplate messagingTemplate;
    private final ChannelRepository channelRepository;
    private final AuthorizationService auth;
    private final DirectContactGate contactGate;

    @MessageMapping("/voice.invite")
    public void relay(VoiceInvite invite, Principal sender) {
        if (invite == null || invite.channelId() == null || invite.to() == null || invite.to().isBlank()) {
            log.info("[voice-invite] dropped a malformed frame from {}", sender.getName());
            return;
        }
        Optional<Channel> channel = channelRepository.findById(invite.channelId());
        if (channel.isEmpty() || channel.get().getType() != ChannelType.VOICE) {
            log.info("[voice-invite] dropped: channel {} is not a voice room (from {})", invite.channelId(), sender.getName());
            return;
        }
        Optional<User> actor = auth.findUser(sender.getName());
        // Eager findUser + VIEW gate: anyone who can SEE the room may invite into it
        // (joining still needs CONNECT — the join path enforces that separately).
        if (actor.isEmpty() || !auth.hasInChannel(actor.get(), channel.get(), Permission.VIEW_CHANNEL)) {
            log.info("[voice-invite] dropped: {} may not view channel {}", sender.getName(), invite.channelId());
            return;
        }
        // M1: the recipient must be reachable (no block, no DM restriction, rate-capped) —
        // an invite card is a personal push exactly like a RING.
        if (!contactGate.canReach(sender.getName(), invite.to(), true)) {
            log.info("[voice-invite] dropped: {} may not reach {}", sender.getName(), invite.to());
            return;
        }
        VoiceInvite stamped = new VoiceInvite(
                channel.get().getId(), channel.get().getName(), sender.getName(), invite.to());
        messagingTemplate.convertAndSendToUser(invite.to(), VOICE_INVITE_QUEUE, stamped);
        log.info("[voice-invite] relayed from={} to={} channel={} ({})", sender.getName(), invite.to(),
                channel.get().getName(), invite.channelId());
    }
}
