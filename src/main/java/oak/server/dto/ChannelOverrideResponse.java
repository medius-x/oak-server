package oak.server.dto;

import oak.server.entity.ChannelRoleOverride;
import oak.server.entity.Permissions;

import java.util.Set;

/**
 * A per-channel permission override for a role. {@code allow}/{@code deny} are the permission
 * <i>names</i> (decoded from the stored bitmasks): a permission in {@code deny} is forced off in this
 * channel, one in {@code allow} forced on, otherwise the role's base permission applies
 * (resolution in {@code AuthorizationService.hasInChannel}).
 */
public record ChannelOverrideResponse(Long channelId, Long roleId, Set<String> allow, Set<String> deny) {

    public static ChannelOverrideResponse from(ChannelRoleOverride override) {
        return new ChannelOverrideResponse(
                override.getChannel().getId(),
                override.getRole().getId(),
                Permissions.names(override.getAllow()),
                Permissions.names(override.getDeny()));
    }
}
