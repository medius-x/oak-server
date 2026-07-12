package oak.server.dto;

/**
 * Room info returned by {@code GET /api/channels/{id}/room}. The client uses {@code janusUrl}
 * (the REST endpoint) to talk to Janus directly, and {@code roomId} (= channel ID) as the
 * Janus videoroom numeric ID.
 */
public record RoomResponse(String janusUrl, long roomId) {
}
