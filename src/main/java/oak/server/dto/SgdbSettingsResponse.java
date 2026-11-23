package oak.server.dto;

/**
 * The SteamGridDB icon layer's settings answer ({@code GET/PATCH /api/server/sgdb},
 * {@code MANAGE_SERVER}): the configured flag and the pause switch — the key
 * itself is WRITE-ONLY (never in a response, never in a log), exactly the
 * IGDB secret discipline. Pausing keeps the key; the CACHED icons stop being
 * SERVED while paused (the user's semantics, 2026-11-23).
 */
public record SgdbSettingsResponse(boolean configured, boolean paused) {
}
