package oak.server.dto;

/**
 * Membership delta pushed to {@code /topic/voice} when a user joins or leaves a voice room (their
 * first session enters, or their last session leaves). Clients seed the sidebar from
 * {@code GET /api/voice/members} and apply these deltas to keep it live.
 */
public record VoiceMemberEvent(String username, Long channelId, VoiceMemberStatus status) {
}
