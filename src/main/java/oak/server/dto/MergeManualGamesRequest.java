package oak.server.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code POST /api/games/merge-manual} (the manual-rule IGDB binding,
 * 2026-11-25): the real game the caller just bound a manual activity rule to —
 * the caller's hours accumulated on virtual manual cards of the SAME
 * (normalized) name move onto it.
 */
public record MergeManualGamesRequest(
        @NotNull @Positive Long igdbId) {
}
