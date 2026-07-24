package oak.server.dto;

import oak.server.entity.Permissions;
import oak.server.entity.Role;

import java.util.Set;

/**
 * Public view of a role. {@code permissions} is the set of granted permission <i>names</i> (decoded
 * from the stored bitmask) so the client renders checkboxes without doing bit math; {@code kind} is
 * {@code EVERYONE}/{@code OWNER}/{@code CUSTOM} so the UI can mark the protected built-ins read-only.
 */
public record RoleResponse(Long id, String name, Integer color, int position,
                           Set<String> permissions, boolean mentionable, boolean hoist, String kind) {

    public static RoleResponse from(Role role) {
        return new RoleResponse(role.getId(), role.getName(), role.getColor(), role.getPosition(),
                Permissions.names(role.getPermissions()),
                role.isMentionable(), role.isHoist(), role.getKind().name());
    }
}
