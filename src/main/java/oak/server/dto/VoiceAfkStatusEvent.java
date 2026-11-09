package oak.server.dto;

/**
 * A roster-presence AFK flip (user request 2026-11-04, reworked the same day):
 * {@code afk=true} when the member's CLIENT reports them away (idle at the computer —
 * its own threshold; the verdict rides {@code /app/roster.afk}), back to {@code false}
 * the moment the client reports them present. The wire name is inherited from the 0.5.0
 * voice-scoped implementation (the dimension is no longer tied to voice rooms, but the
 * topic/event stay so 0.5.x clients render it unchanged). Broadcast on
 * {@code /topic/voice.afk} — the same broadcast residual as {@code /topic/voice} (simple
 * broker, no per-viewer delivery); the pull side ({@code GET /api/voice/afk}) serves the
 * plain away set.
 */
public record VoiceAfkStatusEvent(String username, boolean afk) {
}
