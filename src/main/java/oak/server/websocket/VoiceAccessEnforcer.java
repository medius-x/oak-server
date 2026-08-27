package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.dto.VoiceControlEvent;
import oak.server.entity.Channel;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.service.AuthorizationService;
import oak.server.service.JanusVoiceAccessService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Enforces channel-permission changes against <b>already-live</b> voice sessions. The join-time
 * gates ({@code RoomController}'s {@code /room} REST gate and {@code VoiceMembershipHandler}'s STOMP
 * join) check {@link Permission#CONNECT} only when ENTERING — without this component, a member
 * already sitting in a room survived an admin flipping the channel private (or dropping their
 * allow) indefinitely. Every override mutation ({@code ChannelOverrideService}) and every channel
 * deletion ({@code ChannelService}) now re-resolves the live room here: members who no longer hold
 * CONNECT are evicted with the same forced-exit recipe as a moderator kick
 * ({@code VoiceControlHandler}) — both call {@link #forceLeave}.
 *
 * <p>Runs on whatever thread the caller is on: the HTTP thread inside a {@code @Transactional}
 * override mutation, or the STOMP {@code clientInboundChannel} for moderator kicks. Both are safe —
 * {@link AuthorizationService#findUser} eager-fetches roles,
 * {@link VoiceMembershipTracker} publishes outside its lock, and the Janus calls are
 * fire-and-forget on the {@code oak-janus-admin} executor.
 */
@Component
@RequiredArgsConstructor
public class VoiceAccessEnforcer {

    /** Per-member queue a forced-exit command is pushed to (the target's client tears down its voice). */
    public static final String VOICE_CONTROL_QUEUE = "/queue/voice.control";

    private final VoiceMembershipTracker tracker;
    private final AuthorizationService auth;
    private final JanusVoiceAccessService voiceAccess;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChannelRepository channelRepository;

    /**
     * Evicts every live member of {@code channelId} who no longer holds {@link Permission#CONNECT}
     * (a missing user row counts — a deleted user sitting in voice has no permissions by definition).
     *
     * <p>Called INSIDE the caller's {@code @Transactional} mutation, deliberately: the mask
     * re-resolution runs on the caller's persistence context, so the just-written
     * still-uncommitted override is visible to its queries — Hibernate's AUTO flush pushes the
     * pending change before them. An after-commit hook would instead reopen a transaction per
     * query, and its exceptions propagate to the caller anyway. The pre-commit kick carries the
     * same risk class as the existing pre-commit {@code channels.changed} ping: the rollback
     * window after this call is the caller's return, and a member evicted from a channel whose
     * change then rolled back simply re-joins.
     */
    public void enforceConnect(Long channelId, String actorUsername) {
        List<String> members = liveMembers(channelId);
        if (members.isEmpty()) {
            return; // nobody in voice (the common mutation) — skip the DB roundtrip entirely
        }
        Channel channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null) {
            return; // unreachable from the override hooks (they just loaded/validated it); defensive
        }
        for (String username : members) {
            Optional<User> member = auth.findUser(username);
            if (member.isPresent() && auth.hasInChannel(member.get(), channel, Permission.CONNECT)) {
                continue; // still holds CONNECT (the VIEW master gate included) — stays
            }
            forceLeave(channelId, username, actorUsername);
        }
    }

    /**
     * Evicts EVERY live member of {@code channelId} — the channel-deletion counterpart of
     * {@link #enforceConnect}, with no permission recheck: the channel is going away, so CONNECT
     * resolution is moot. Never touches the database (the registry snapshot is the only input), so
     * the caller may place it anywhere inside its transaction without adding rollback surface.
     */
    public void kickAll(Long channelId, String actorUsername) {
        for (String username : liveMembers(channelId)) {
            forceLeave(channelId, username, actorUsername);
        }
    }

    /**
     * The shared forced-exit recipe (registry truth first, then the forced layers, then the
     * cooperative push so an honest client tears down gracefully): exits every STOMP session of the
     * user in the channel (&rarr; {@code LEFT} on {@code /topic/voice} on the 1&rarr;0 boundary),
     * plugin-kicks their Janus publishers (media torn down instantly — also covers a rogue client),
     * revokes their room tokens (re-entry blocked until a fresh {@code /room} re-checks CONNECT),
     * and pushes a targeted {@code KICKED} on {@link #VOICE_CONTROL_QUEUE}.
     */
    public void forceLeave(Long channelId, String username, String byUsername) {
        tracker.kick(channelId, username);
        voiceAccess.forceKick(channelId, username);
        voiceAccess.revokeTokens(channelId, username);
        messagingTemplate.convertAndSendToUser(username, VOICE_CONTROL_QUEUE,
                new VoiceControlEvent("KICKED", channelId, byUsername));
    }

    /**
     * The usernames currently holding at least one session in the channel — a copied snapshot, so
     * it is safe to iterate while {@link #forceLeave} mutates the registry underneath. Empty when
     * nobody is in voice.
     */
    private List<String> liveMembers(Long channelId) {
        return tracker.snapshot().stream()
                .filter(room -> channelId.equals(room.channelId()))
                .findFirst()
                .map(VoiceChannelMembersDto::usernames)
                .orElse(List.of());
    }
}
