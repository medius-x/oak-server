package oak.server.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Server Settings → Integrations → IGDB writes ({@code PATCH /api/server/igdb},
 * MANAGE_SERVER). Null fields keep the stored value. Credential fields: an
 * EMPTY STRING clears the credential (the Disconnect button — both gone); the
 * pause switch and the credentials are FULLY INDEPENDENT — disconnecting keeps
 * the switch position, pausing keeps the pair. Twitch issues both strings as
 * plain alphanumeric.
 */
public record UpdateIgdbCredentialsRequest(
        @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9]*$") String clientId,
        @Size(max = 128) @Pattern(regexp = "^[a-zA-Z0-9]*$") String clientSecret,
        Boolean paused) {
}
