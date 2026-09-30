package oak.server.dto;

/**
 * The stored TTL after a settings change (also what {@code GET /api/invites} reports).
 */
public record InviteSettingsResponse(Integer ttlMinutes) {
}
