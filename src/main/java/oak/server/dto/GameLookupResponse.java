package oak.server.dto;

import java.util.List;

/**
 * One run of the IGDB match ladder. {@code game} is the CONFIDENT answer (an exact
 * Steam-appid hit, or a search result whose normalized name equals the query) — the
 * client may show it without asking. When the ladder is only CLOSE, {@code game} is
 * null and {@code candidates} carries the top search results for the user to pick
 * from (the pick then comes back via {@code POST /api/games/confirm} and is pinned in
 * the match cache for everyone). Both empty = nothing on IGDB matches this exe.
 */
public record GameLookupResponse(GameCard game, List<GameCard> candidates) {

    public static GameLookupResponse resolved(GameCard game) {
        return new GameLookupResponse(game, List.of());
    }

    public static GameLookupResponse candidates(List<GameCard> candidates) {
        return new GameLookupResponse(null, List.copyOf(candidates));
    }

    public static GameLookupResponse empty() {
        return new GameLookupResponse(null, List.of());
    }
}
