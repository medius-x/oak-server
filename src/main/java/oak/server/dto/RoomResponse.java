package oak.server.dto;

/**
 * Room info returned by {@code GET /api/channels/{id}/room}. The client uses {@code janusUrl}
 * (the REST endpoint) to talk to Janus directly and {@code roomId} (= channel ID) as the Janus
 * videoroom numeric ID. {@code token} and {@code pin} are the room's access credentials (Janus runs
 * with {@code token_auth = true} and rooms carry a join pin — see {@code JanusVoiceAccessService}):
 * the token goes as {@code ?token=} on every gateway request, the pin inside every {@code join}.
 * Wire contract: fields are appended at the end — deploy paired with the oak-client mirror.
 */
public record RoomResponse(String janusUrl, long roomId, String token, String pin) {
}
