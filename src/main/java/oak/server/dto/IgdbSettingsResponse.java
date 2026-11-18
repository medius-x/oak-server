package oak.server.dto;

/**
 * The settings-screen view of the IGDB integration: the Client ID (not secret, shown
 * so the admin can tell which application is bound) and whether BOTH credentials are
 * set. The secret is deliberately absent — it is write-only through the API.
 */
public record IgdbSettingsResponse(String clientId, boolean configured) {
}
