package oak.server.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Server Settings → IGDB credentials ({@code PATCH /api/server/igdb}, MANAGE_SERVER).
 * Null fields keep the stored value; an EMPTY STRING clears it (switching the
 * integration off) — the UPDATE-vs-CLEAR ambiguity a bare optional string cannot
 * express. Twitch issues both as plain alphanumeric strings.
 */
public record UpdateIgdbCredentialsRequest(
        @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9]*$") String clientId,
        @Size(max = 128) @Pattern(regexp = "^[a-zA-Z0-9]*$") String clientSecret) {
}
