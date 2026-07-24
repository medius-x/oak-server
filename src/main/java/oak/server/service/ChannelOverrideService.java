package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelOverrideResponse;
import oak.server.dto.UpsertOverrideRequest;
import oak.server.entity.Channel;
import oak.server.entity.ChannelRoleOverride;
import oak.server.entity.Permissions;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.RoleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Per-channel permission overrides (Discord-style private channels). All mutations require
 * {@link Permission#MANAGE_CHANNELS}. An override is keyed by (channel, role) and upserted —
 * {@code allow}/{@code deny} are stored as bitmasks and decoded to/from permission names at the edges.
 */
@Service
@RequiredArgsConstructor
public class ChannelOverrideService {

    private final ChannelRepository channelRepository;
    private final RoleRepository roleRepository;
    private final ChannelRoleOverrideRepository overrideRepository;
    private final AuthorizationService auth;

    @Transactional(readOnly = true)
    public List<ChannelOverrideResponse> list(Long channelId) {
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
        return ChannelOverrideResponse.from(override);
    }

    @Transactional
    public void delete(Long channelId, Long roleId, String actorUsername) {
        auth.requireServer(auth.requireUser(actorUsername), Permission.MANAGE_CHANNELS);
        overrideRepository.findByChannelIdAndRoleId(channelId, roleId)
                .ifPresent(overrideRepository::delete);
    }

    private Channel requireChannel(Long channelId) {
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
    }
}
