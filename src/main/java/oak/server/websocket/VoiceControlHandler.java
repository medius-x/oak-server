package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.dto.VoiceControlCommand;
import oak.server.dto.VoiceControlEvent;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Optional;

/**
 * Server-side voice control: a member with the matching permission mutes/deafens/kicks another in a
 * voice room. Mute/deafen set state in {@link VoiceMembershipTracker} (which broadcasts the new state on
 * {@code /topic/voice.state} so everyone — including the target, who complies — sees it); kick force-exits
 * the target's sessions (→ {@code LEFT} on {@code /topic/voice}) and pushes a targeted {@code KICKED}
 * command to the target ({@code /user/queue/voice.control}) so its client tears down its Janus connection.
 *
 * <p>Must be {@code @Controller} (Spring scans {@code @MessageMapping} only on {@code @Controller} beans —
 * see {@link CallSignalingHandler}). Permission + hierarchy checks go through {@link AuthorizationService},
 * whose {@code findUser} eager-fetches roles, so this is safe on the STOMP {@code clientInboundChannel}
 * thread (no OpenEntityManagerInView there). A denied control is a silent no-op.
 */
@Controller
@RequiredArgsConstructor
public class VoiceControlHandler {

    /** Per-member queue a kick command is pushed to (the target's client disconnects its voice). */
    public static final String VOICE_CONTROL_QUEUE = "/queue/voice.control";

    private final VoiceMembershipTracker tracker;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/voice.mute")
    public void mute(VoiceControlCommand command, Principal actor) {
        if (!canControl(command, actor, Permission.MUTE_MEMBERS)) {
            return;
        }
        tracker.setMuted(command.channelId(), command.targetUsername(), Boolean.TRUE.equals(command.muted()));
    }

    @MessageMapping("/voice.deafen")
    public void deafen(VoiceControlCommand command, Principal actor) {
        if (!canControl(command, actor, Permission.DEAFEN_MEMBERS)) {
            return;
        }
        tracker.setDeafened(command.channelId(), command.targetUsername(), Boolean.TRUE.equals(command.deafened()));
    }

    @MessageMapping("/voice.kick")
    public void kick(VoiceControlCommand command, Principal actor) {
        if (!canControl(command, actor, Permission.MOVE_MEMBERS)) {
            return;
        }
        tracker.kick(command.channelId(), command.targetUsername());
        messagingTemplate.convertAndSendToUser(command.targetUsername(), VOICE_CONTROL_QUEUE,
                new VoiceControlEvent("KICKED", command.channelId(), actor.getName()));
    }

    /** Resolves actor + target, checks the permission and the role hierarchy (canActOn). */
    private boolean canControl(VoiceControlCommand command, Principal actor, Permission permission) {
        if (command == null || command.channelId() == null || command.targetUsername() == null) {
            return false;
        }
        Optional<User> actorUser = auth.findUser(actor.getName());
        Optional<User> target = auth.findUser(command.targetUsername());
        if (actorUser.isEmpty() || target.isEmpty()) {
            return false;
        }
        return auth.hasServer(actorUser.get(), permission) && auth.canActOn(actorUser.get(), target.get());
    }
}
