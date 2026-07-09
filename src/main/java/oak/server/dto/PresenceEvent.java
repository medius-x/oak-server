package oak.server.dto;

/**
 * Presence delta pushed to {@code /topic/presence} when a user's online state flips
 * (their first session connects, or their last session disconnects). Clients seed the
 * roster from {@code GET /api/presence} and then apply these deltas to keep it live.
 */
public record PresenceEvent(String username, PresenceStatus status) {
}
