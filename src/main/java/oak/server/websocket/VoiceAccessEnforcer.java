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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Enforces channel-permission changes against <b>already-live</b> voice sessions. The join-time
 * gates ({@code RoomController}'s {@code /room} REST gate and {@code VoiceMembershipHandler}'s STOMP
 * join) check permissions only when ENTERING — without this component, a member already sitting in
 * a room survived an admin flipping the channel private (or dropping their allow) indefinitely.
 * Two rights are re-derived per member:
 * <ul>
 *   <li>{@link Permission#CONNECT} lost &rarr; forced exit (the same recipe as a moderator kick,
 *       {@link VoiceControlHandler} — both call {@link #forceLeave});</li>
 *   <li>{@link Permission#SPEAK} (security round 6): lost &rarr; the derived permission-mute
 *       ({@link VoiceMembershipTracker#setPermMuted} + gateway {@code moderate}); regained with
 *       the mark alive &rarr; the mark lifts. Neither touches an ordinary moderator mute.</li>
 * </ul>
 * Trigger points: every override mutation ({@code ChannelOverrideService}), a role-mask edit or
 * role deletion ({@code RoleService} — affects every holder in every room), a member's role-set
 * replacement ({@code MemberService}), and channel deletion ({@code ChannelService}).
 *
 * <p><b>Two registries feed the sweeps:</b> the STOMP membership tracker (honest clients) and the
 * Janus token registry ({@code tokenHoldersByChannel} — a rogue talking to the gateway DIRECTLY,
 * without a STOMP voice.join, is invisible to the tracker, but its token was minted through
 * {@code /room} under a real username). The union covers both.
 *
 * <p>Runs on whatever thread the caller is on: the HTTP thread inside a {@code @Transactional}
 * mutation, or the STOMP {@code clientInboundChannel} for moderator kicks. Both are safe —
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
     * Re-derives every live member's CONNECT and SPEAK in {@code channelId} (a missing user row
     * counts — a deleted user sitting in voice has no permissions by definition). Input is the
     * union of the STOMP registry and the channel's live token holders.
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
    public void enforceChannelChange(Long channelId, String actorUsername) {
        Set<String> members = new TreeSet<>(liveMembers(channelId));
        members.addAll(liveTokenHolders(channelId));
        if (members.isEmpty()) {
            return; // nobody in voice and no live tokens (the common mutation) — skip the DB roundtrip
        }
        Channel channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null) {
            return; // unreachable from the override hooks (they just loaded/validated it); defensive
        }
        for (String username : members) {
            enforceAgainst(channel, username, actorUsername);
        }
    }

    /**
     * Rechecks ONE member across every room they occupy (both registries) — the hook for a member's
     * role-set replacement ({@code MemberService}): only that member's effective mask changed, so
     * only their rooms are worth a database look.
     */
    public void enforceForMember(String username, String actorUsername) {
        for (Long channelId : occupiedChannels(username)) {
            channelRepository.findById(channelId).ifPresent(channel ->
                    enforceAgainst(channel, username, actorUsername));
        }
    }

    /**
     * Rechecks EVERY occupied room (both registries) — the hook for a role-mask edit or role
     * deletion ({@code RoleService}): a role touches an arbitrary set of holders in an arbitrary
     * set of channels, so the only correct scope is everything. Cost is bounded by the occupied
     * rooms (an unoccupied channel costs one in-memory snapshot read) — the same small-instance
     * trade-off as the per-member override rechecks.
     */
    public void enforceEverywhere(String actorUsername) {
        Set<Long> channelIds = new TreeSet<>();
        for (VoiceChannelMembersDto room : tracker.snapshot()) {
            channelIds.add(room.channelId());
        }
        channelIds.addAll(voiceAccess.tokenHoldersByChannel().keySet());
        for (Long channelId : channelIds) {
            enforceChannelChange(channelId, actorUsername);
        }
    }

    /**
     * Evicts EVERY live member of {@code channelId} — the channel-deletion counterpart of
     * {@link #enforceChannelChange}, with no permission recheck: the channel is going away, so CONNECT
     * resolution is moot. Never touches the database (the registry snapshot is the only input), so
     * the caller may place it anywhere inside its transaction without adding rollback surface.
     */
    public void kickAll(Long channelId, String actorUsername) {
        for (String username : liveMembers(channelId)) {
            forceLeave(channelId, username, actorUsername);
        }
    }

    /**
     * Force-removes {@code username} from EVERY room they occupy (union of the STOMP registry and the
     * Janus token registry) — the member-removal hook. No permission recheck and no database access:
     * a removed account's CONNECT resolution is moot ({@link #kickAll}'s stance). The victim receives
     * both the KICKED voice-control push and the member.kick push — both tear down, harmlessly.
     */
    public void evictEverywhere(String username, String actorUsername) {
        for (Long channelId : occupiedChannels(username)) {
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

    /** Usernames still holding a live gateway token for the channel — the non-STOMP half of the sweep. */
    private List<String> liveTokenHolders(Long channelId) {
        return voiceAccess.tokenHoldersByChannel().getOrDefault(channelId, List.of());
    }

    /** Every channel the member occupies in EITHER registry — the shared scope of the per-member sweeps. */
    private Set<Long> occupiedChannels(String username) {
        Set<Long> channelIds = new TreeSet<>();
        for (VoiceChannelMembersDto room : tracker.snapshot()) {
            if (room.usernames().contains(username)) {
                channelIds.add(room.channelId());
            }
        }
        for (Map.Entry<Long, List<String>> room : voiceAccess.tokenHoldersByChannel().entrySet()) {
            if (room.getValue().contains(username)) {
                channelIds.add(room.getKey());
            }
        }
        return channelIds;
    }

    /** The per-member half of a sweep: evict on lost CONNECT, otherwise derive the SPEAK permission-mute.
     *  The gateway layer follows the tracker's UNION, not the toggled value — lifting a permission-mute
     *  must not re-open the audio of a member still muted by another origin (an AFK moderator mute). */
    private void enforceAgainst(Channel channel, String username, String actorUsername) {
        Optional<User> member = auth.findUser(username);
        if (member.isEmpty() || !auth.hasInChannel(member.get(), channel, Permission.CONNECT)) {
            forceLeave(channel.getId(), username, actorUsername);
            return;
        }
        boolean maySpeak = auth.hasInChannel(member.get(), channel, Permission.SPEAK);
        boolean marked = tracker.isPermMuted(channel.getId(), username);
        if (maySpeak && marked) {
            tracker.setPermMuted(channel.getId(), username, false);
            voiceAccess.moderateAudio(channel.getId(), username, tracker.isMuted(channel.getId(), username));
        } else if (!maySpeak && !marked) {
            tracker.setPermMuted(channel.getId(), username, true);
            voiceAccess.moderateAudio(channel.getId(), username, true);
        }
    }
}
