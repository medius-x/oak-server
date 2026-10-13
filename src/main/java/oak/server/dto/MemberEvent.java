package oak.server.dto;

/**
 * Pushed to {@code /topic/members} on membership/role changes. Clients refetch
 * {@code GET /api/members} (and re-evaluate their own gate on a role change). Types:
 * {@code joined} (a fresh registration — pushed AFTER COMMIT alongside the
 * {@code /topic/users} roster push, live VPS 2026-10-13), {@code rolesChanged},
 * {@code renamed}, {@code kicked}, {@code banned}, {@code removed}.
 */
public record MemberEvent(String type, Long userId) {
}
