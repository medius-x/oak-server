package oak.server.dto;

/**
 * The settings-screen view of the IGDB integration: the Client ID (not secret,
 * shown so the admin can tell which application is bound), whether BOTH
 * credentials are stored ({@code configured} — the fields are filled in), and
 * the pause switch POSITION ({@code paused} — stored independently: a
 * disconnect keeps it, so re-entering the keys restores exactly the state the
 * admin left). Working combination: {@code configured && !paused}. The secret
 * is deliberately absent — it is write-only through the API.
 */
public record IgdbSettingsResponse(String clientId, boolean configured, boolean paused) {
}
