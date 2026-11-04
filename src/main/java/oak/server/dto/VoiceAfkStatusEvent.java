package oak.server.dto;

/**
 * A roster-presence AFK flip for a voice member (user request 2026-11-04): {@code afk=true}
 * when the member's last activity heartbeat is older than the AFK timeout, back to
 * {@code false} the moment a fresh heartbeat arrives. Broadcast on
 * {@code /topic/voice.afk} — the same broadcast residual as {@code /topic/voice} (simple
 * broker, no per-viewer delivery); the pull side ({@code GET /api/voice/afk}) is
 * VIEW-filtered per room.
 */
public record VoiceAfkStatusEvent(String username, boolean afk) {
}
