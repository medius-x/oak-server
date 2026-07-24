package oak.server.dto;

/**
 * Pushed to {@code /topic/members} when a member's roles change or they are kicked/banned. Clients
 * refetch {@code GET /api/members} (and re-evaluate their own gate on a role change).
 */
public record MemberEvent(String type, Long userId) {
}
