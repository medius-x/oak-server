package oak.server.dto;

import oak.server.entity.IgdbGame;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * A game card served by the IGDB integration: the stable projection of an IGDB game
 * the client renders next to the roster activity line (name, cover, genres, release
 * year). The cover URL is assembled CLIENT-side from {@code coverImageId} — the CDN
 * ({@code images.igdb.com}) is public and size presets are a rendering concern.
 * Genres ride along as id+name pairs: the future achievements tier aggregates played
 * hours per genre ("100 hours in shooters"), so the card is self-sufficient offline.
 */
public record GameCard(long igdbId, String name, String coverImageId,
                       List<GenreCard> genres, Long firstReleaseDate, String iconUrl, String pendingIconUrl) {

    /** The embedded genre projection ({@code genres.name} expand of /v4/games). */
    public record GenreCard(long id, String name) {
    }

    /** The genres column codec: stored as a JSON array string in {@link IgdbGame}. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static GameCard from(IgdbGame game) {
        List<GenreCard> genres = new ArrayList<>();
        JsonNode array = JSON.readTree(game.getGenres());
        if (array.isArray()) {
            array.forEach(genre -> genres.add(new GenreCard(genre.path("id").asLong(),
                    genre.path("name").asText(""))));
        }
        // iconUrl is DELIVERED-gated (2026-11-24): sgdb_icon_url now also serves
        // as the PENDING marker (target chosen, bytes still owed), so the card
        // must not claim an icon before the bytes exist. pendingIconUrl (the
        // same column while undelivered) lets a healthy-networked client fetch
        // the icon itself and offer the bytes — see POST /api/games/{id}/icon.
        boolean delivered = game.getSgdbIconBytes() != null;
        return new GameCard(game.getIgdbId(), game.getName(), game.getCoverImageId(),
                List.copyOf(genres), game.getFirstReleaseDate(),
                delivered ? game.getSgdbIconUrl() : null,
                delivered ? null : game.getSgdbIconUrl());
    }

    /** Serializes the {@code /v4/games} genre expand into the storage JSON form. */
    public static String genresToJson(JsonNode genresNode) {
        return genresNode == null || !genresNode.isArray() ? "[]" : genresNode.toString();
    }
}
