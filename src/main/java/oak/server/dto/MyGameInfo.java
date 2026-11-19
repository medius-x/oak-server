package oak.server.dto;

import java.time.Instant;
import java.util.List;

/**
 * One row of {@code GET /api/games/my} — the user's play history aggregate:
 * the card projection (name/cover/genres from the IGDB cache) plus the summed
 * PLAYTIME across every session (an open session counts up to now) and when
 * the game was last played. The achievements tier will aggregate the same
 * sessions further (per-genre hours); this is the per-user settings view.
 */
public record MyGameInfo(long igdbId, String name, String coverImageId,
                         List<GameCard.GenreCard> genres,
                         long totalSeconds, Instant lastPlayedAt) {
}
