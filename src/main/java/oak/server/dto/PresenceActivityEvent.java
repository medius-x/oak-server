package oak.server.dto;

/**
 * Activity delta pushed to {@code /topic/presence.activity} when a user's activity
 * CHANGES (set, replaced, or cleared — {@code null} activity means cleared).
 * Clients seed from {@code GET /api/presence/activities} and apply these deltas.
 */
public record PresenceActivityEvent(String username, PresenceActivity activity) {
}
