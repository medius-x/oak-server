package oak.server.dto;

import oak.server.entity.ChannelUserOverride;
import oak.server.entity.Permissions;

import java.util.Set;

/**
 * A per-channel permission override for a single USER (Discord's "member override"). Carries the
 * username so the client can label its access checklist without a second join; the
 * {@code allow}/{@code deny} semantics mirror {@link ChannelOverrideResponse}, except this row
 * resolves as the TOP layer (a member allow beats an @everyone/role deny — see
 * {@code AuthorizationService.resolveChannelMask}).
 */
public record ChannelUserOverrideResponse(Long channelId, Long userId, String username,
                                          Set<String> allow, Set<String> deny) {

    public static ChannelUserOverrideResponse from(ChannelUserOverride override) {
        return new ChannelUserOverrideResponse(
                override.getChannel().getId(),
                override.getUser().getId(),
                override.getUser().getUsername(),
                Permissions.names(override.getAllow()),
                Permissions.names(override.getDeny()));
    }
}
