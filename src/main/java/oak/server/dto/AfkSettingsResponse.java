package oak.server.dto;

/** GET /api/server/afk — the AFK sweep settings (channel null = the feature is off). */
public record AfkSettingsResponse(Long afkChannelId, Integer afkTimeoutMinutes) {
}
