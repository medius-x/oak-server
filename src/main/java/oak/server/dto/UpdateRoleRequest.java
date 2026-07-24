package oak.server.dto;

import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Partial update of a role (PATCH). Every field is optional — {@code null} means "leave unchanged".
 * {@code @Size} passes {@code null}, so an absent {@code name} is a no-op (validation only rejects an
 * over-long non-null value).
 */
public record UpdateRoleRequest(
        @Size(max = 100) String name,
        Set<String> permissions,
        Integer color,
        Boolean mentionable,
        Boolean hoist) {
}
