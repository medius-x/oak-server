package oak.server.dto;

/**
 * Pushed to {@code /topic/roles} when a role is created/updated/deleted/reordered. Carries only the
 * change kind (+ id when single-role) — clients refetch {@code GET /api/roles} to stay in sync, which
 * keeps the payload immune to DTO drift.
 */
public record RoleEvent(String type, Long roleId) {
}
