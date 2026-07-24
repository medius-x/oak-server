package oak.server.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Reorders custom roles by position. {@code roleIds} lists the custom roles top→bottom (highest
 * authority first); the server reassigns dense positions preserving that order. {@code @everyone} and
 * {@code OWNER} keep their fixed positions and must not appear here.
 */
public record ReorderRolesRequest(@NotNull List<Long> roleIds) {
}
