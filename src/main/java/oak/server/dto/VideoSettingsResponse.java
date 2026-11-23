package oak.server.dto;

/**
 * The voice-room video codec settings answer ({@code GET/PATCH /api/server/video},
 * {@code MANAGE_SERVER}, 2026-11-23 V24): the codec Janus offers when a room
 * is CREATED — {@code vp8} / {@code vp9} / {@code h264}. The answer always
 * carries the EFFECTIVE value (a null column reads as the historical default
 * {@code vp8}); live rooms keep the codec they were created with until they
 * empty, so a change lands on the next room.
 */
public record VideoSettingsResponse(String codec) {
}
