package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.dto.VoiceControlCommand;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import oak.server.service.JanusVoiceAccessService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Optional;

/**
 * Server-side voice control: a member with the matching permission mutes/deafens/kicks another in a
 * voice room — or toggles mute/deafen on THEMSELVES (no permission needed, Discord-style; the self
 * path writes the tracker's SELF state set, so a self-unmute never lifts a moderator's mute).
 * Mute/deafen set state in {@link VoiceMembershipTracker} (which broadcasts the new state on
 * {@code /topic/voice.state} so everyone — including the target, who complies — sees it); kick delegates
 * to {@link VoiceAccessEnforcer#forceLeave} — the shared four-step forced-exit recipe (registry exit →
 * Janus plugin-kick → token revocation → targeted {@code KICKED} push on
 * {@code /user/queue/voice.control}) also used to evict members who lost channel access live.
 *
 * <p><b>Forced layer</b> (works against a rogue client ignoring the cooperative pushes): every action
 * ALSO goes to Janus through {@link JanusVoiceAccessService} — mute maps to videoroom {@code moderate}
 * (the gateway stops relaying the target's audio), kick maps to plugin-level {@code kick} (media torn
 * down instantly) plus token revocation (blocks re-entry until a fresh /room). Both are fire-and-forget
 * on a dedicated executor — this handler runs on the STOMP {@code clientInboundChannel} and must not
 * block. Deafen has NO forced counterpart: {@code moderate} only gates the target's outgoing m-lines,
 * so "making a rogue deaf" is impossible without kicking them — it stays cooperative (documented).
 *
 * <p>Must be {@code @Controller} (Spring scans {@code @MessageMapping} only on {@code @Controller} beans —
 * see {@code CallSignalingHandler}). Permission + hierarchy checks go through {@link AuthorizationService},
 * whose {@code findUser} eager-fetches roles, so this is safe on the STOMP {@code clientInboundChannel}
 * thread (no OpenEntityManagerInView there). A denied control is a silent no-op.
 */
@Controller
@RequiredArgsConstructor
public class VoiceControlHandler {

    private final VoiceMembershipTracker tracker;
    private final AuthorizationService auth;
    private final JanusVoiceAccessService voiceAccess;
    private final VoiceAccessEnforcer voiceEnforcer;

    @MessageMapping("/voice.mute")
    public void mute(VoiceControlCommand command, Principal actor) {
        if (isSelf(command, actor)) {
            // Self-mute needs no permission (everyone may silence their own mic, Discord-style) and
            // no DB lookup — the STOMP principal is already authenticated. Touches only the SELF
            // state set: a self-unmute can never lift a moderator's mute (see VoiceMembershipTracker).
            boolean mute = Boolean.TRUE.equals(command.muted());
            tracker.setSelfMuted(command.channelId(), command.targetUsername(), mute);
            voiceAccess.moderateAudio(command.channelId(), command.targetUsername(), mute);
            return;
        }
        if (!canControl(command, actor, Permission.MUTE_MEMBERS)) {
            return;
        }
        boolean mute = Boolean.TRUE.equals(command.muted());
        tracker.setMuted(command.channelId(), command.targetUsername(), mute);
        voiceAccess.moderateAudio(command.channelId(), command.targetUsername(), mute);
    }

    @MessageMapping("/voice.deafen")
    public void deafen(VoiceControlCommand command, Principal actor) {
        if (isSelf(command, actor)) {
            // Self-deafen: no permission needed, cooperative only (see the class javadoc).
            tracker.setSelfDeafened(command.channelId(), command.targetUsername(),
                    Boolean.TRUE.equals(command.deafened()));
            return;
        }
        if (!canControl(command, actor, Permission.DEAFEN_MEMBERS)) {
            return;
        }
        // Cooperative only — see the class javadoc (no forced counterpart exists).
        tracker.setDeafened(command.channelId(), command.targetUsername(), Boolean.TRUE.equals(command.deafened()));
    }

    @MessageMapping("/voice.kick")
    public void kick(VoiceControlCommand command, Principal actor) {
        if (!canControl(command, actor, Permission.MOVE_MEMBERS)) {
            return;
        }
        voiceEnforcer.forceLeave(command.channelId(), command.targetUsername(), actor.getName());
    }

    /** True for a well-formed command whose target is the actor themself — the self-toggle path
     *  (no permission, no hierarchy, no DB lookup; the STOMP principal is already authenticated). */
    private static boolean isSelf(VoiceControlCommand command, Principal actor) {
        return command != null && command.channelId() != null && command.targetUsername() != null
                && actor != null && command.targetUsername().equals(actor.getName());
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
