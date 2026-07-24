package oak.server.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * Replaces a member's roles with exactly the given set (the OWNER role, if held, is always retained —
 * it can only be transferred, not stripped by a non-owner). All listed roles must be custom and below
 * the actor's top role.
 */
public record AssignRolesRequest(@NotNull Set<Long> roleIds) {
}
