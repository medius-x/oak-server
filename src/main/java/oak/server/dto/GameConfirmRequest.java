package oak.server.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Pins a user's pick from an ambiguous {@code GET /api/games/lookup}: the same
 * identifying inputs the lookup carried (whichever are present) plus the picked IGDB
 * game id. The server re-derives the SAME match keys and records the hit, so the next
 * lookup for that exe resolves without IGDB and without asking again.
 */
public record GameConfirmRequest(
        String appId,
        String exeName,
        String folderName,
        @NotNull @Positive Long igdbId) {
}
