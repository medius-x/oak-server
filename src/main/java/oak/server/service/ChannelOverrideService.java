package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelOverrideResponse;
import oak.server.dto.ChannelUserOverrideResponse;
import oak.server.dto.UpsertOverrideRequest;
import oak.server.entity.Channel;
import oak.server.entity.ChannelRoleOverride;
import oak.server.entity.ChannelUserOverride;
import oak.server.entity.Permissions;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.ChannelUserOverrideRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.websocket.VoiceAccessEnforcer;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Per-channel permission overrides (Discord-style private channels). All mutations require
 * {@link Permission#MANAGE_CHANNELS}. Role overrides are keyed by (channel, role) and member
 * overrides by (channel, user) — both upserted; {@code allow}/{@code deny} are stored as bitmasks
 * and decoded to/from permission names at the edges. Every successful mutation pings
 * {@link ChannelService#CHANNELS_CHANGED_TOPIC} so clients refetch their viewer-filtered channel
 * list (visibility / lock state may have flipped for someone) — and re-enforces CONNECT on the
 * channel's LIVE voice session ({@link VoiceAccessEnforcer}): members who just lost access are
 * force-evicted from the room they are already sitting in, not merely locked out of re-entry.
 */
@Service
@RequiredArgsConstructor
public class ChannelOverrideService {

    private final ChannelRepository channelRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ChannelRoleOverrideRepository overrideRepository;
    private final ChannelUserOverrideRepository userOverrideRepository;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;
    private final VoiceAccessEnforcer voiceEnforcer;

    @Transactional(readOnly = true)
    public List<ChannelOverrideResponse> list(Long channelId, String actorUsername) {
        // M2: the matrices disclose who is allow-listed into a channel — same gate as the
        // mutations (previously any member could read every channel's ACL by id sweep,
        // defeating hidden channels' member confidentiality).
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        requireChannel(channelId);
        return overrideRepository.findByChannelId(channelId).stream()
                .map(ChannelOverrideResponse::from)
                .toList();
    }

    @Transactional
    public ChannelOverrideResponse upsert(Long channelId, Long roleId, UpsertOverrideRequest request, String actorUsername) {
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        Channel channel = requireChannel(channelId);
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found"));
        long allow = Permissions.fromNames(request.allow());
        long deny = Permissions.fromNames(request.deny());
        ChannelRoleOverride override = overrideRepository.findByChannelIdAndRoleId(channelId, roleId)
                .orElseGet(() -> new ChannelRoleOverride(channel, role, 0L, 0L));
        override.setAllow(allow);
        override.setDeny(deny);
        override = overrideRepository.save(override);
        pingChannelsChanged();
        voiceEnforcer.enforceChannelChange(channelId, actorUsername);
        return ChannelOverrideResponse.from(override);
    }

    @Transactional
    public void delete(Long channelId, Long roleId, String actorUsername) {
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        overrideRepository.findByChannelIdAndRoleId(channelId, roleId)
                .ifPresent(overrideRepository::delete);
        pingChannelsChanged();
        voiceEnforcer.enforceChannelChange(channelId, actorUsername);
    }

    // --- member-level (per-user) overrides: the private-channel "allow these specific people" ---

    @Transactional(readOnly = true)
    public List<ChannelUserOverrideResponse> listUserOverrides(Long channelId, String actorUsername) {
        // M2: usernames with private grants to a hidden channel — the most sensitive read here.
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        requireChannel(channelId);
        return userOverrideRepository.findWithUserByChannelId(channelId).stream()
                .map(ChannelUserOverrideResponse::from)
                .toList();
    }

    @Transactional
    public ChannelUserOverrideResponse upsertUserOverride(Long channelId, Long userId,
            UpsertOverrideRequest request, String actorUsername) {
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        Channel channel = requireChannel(channelId);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        long allow = Permissions.fromNames(request.allow());
        long deny = Permissions.fromNames(request.deny());
        ChannelUserOverride override = userOverrideRepository.findByChannelIdAndUserId(channelId, userId)
                .orElseGet(() -> new ChannelUserOverride(channel, target, 0L, 0L));
        override.setAllow(allow);
        override.setDeny(deny);
        override = userOverrideRepository.save(override);
        pingChannelsChanged();
        voiceEnforcer.enforceChannelChange(channelId, actorUsername);
        return ChannelUserOverrideResponse.from(override);
    }

    @Transactional
    public void deleteUserOverride(Long channelId, Long userId, String actorUsername) {
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        userOverrideRepository.findByChannelIdAndUserId(channelId, userId)
                .ifPresent(userOverrideRepository::delete);
        pingChannelsChanged();
        voiceEnforcer.enforceChannelChange(channelId, actorUsername);
    }

    private void pingChannelsChanged() {
        messagingTemplate.convertAndSend(ChannelService.CHANNELS_CHANGED_TOPIC, System.currentTimeMillis());
    }

    private Channel requireChannel(Long channelId) {
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
    }
}
