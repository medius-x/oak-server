package oak.server.service.igdb;

import lombok.extern.slf4j.Slf4j;
import oak.server.dto.GameCard;
import oak.server.dto.GameLookupResponse;
import oak.server.entity.IgdbGame;
import oak.server.entity.IgdbMatchCache;
import oak.server.repository.IgdbGameRepository;
import oak.server.repository.IgdbMatchCacheRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The IGDB match ladder, wrapped in the local cache (V19). Per lookup at most ONE
 * upstream query per rung: (1) the exact Steam-appid route — {@code external_games}
 * with {@code external_game_source = 1} (the legacy {@code category} field stopped
 * filtering, live-probed 2026-11-18, and bare {@code uid}s collide across sources);
 * (2) the fuzzy-search route over the normalized folder/exe names, where only an
 * EXACT normalized-name equality is trusted as a confident answer — anything less is
 * returned as candidates for the user to pin via confirm.
 *
 * <p>Cache discipline: a hit is permanent (a game card is stable); a MISS is recorded
 * with a {@link #MISS_TTL} re-check (the game may have been added to IGDB since); an
 * ambiguous result (candidates, no exact match) pins NOTHING — the next lookup
 * re-searches honestly until a user confirms. All keys derive from
 * {@link IgdbNames}, so a confirm writes the very keys the next lookup reads.
 */
@Slf4j
@Service
public class IgdbGameService {

    /** How long a recorded miss stays authoritative before IGDB is asked again. */
    static final Duration MISS_TTL = Duration.ofDays(7);

    /** How long a cached card serves before a lazy refresh is queued ("once a
     *  day when someone actually plays the game" — the anti-hammering rule:
     *  IGDB is only touched by live interest, never on a schedule). */
    static final Duration CARD_REFRESH_TTL = Duration.ofDays(1);

    /** Upstream search width — also the candidates cap of one lookup response. */
    private static final int SEARCH_LIMIT = 5;

    private static final String CARD_FIELDS = "fields name,cover.image_id,genres.name,first_release_date;";

    /** The lazy refresh lane: one daemon thread — refreshes are rare (a day per
     *  game) and must never delay a lookup's own answer. */
    private final java.util.concurrent.ExecutorService refreshLane =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "igdb-card-refresh");
                thread.setDaemon(true);
                return thread;
            });
    /** Card ids with a refresh already queued (a burst of lookups must not
     *  queue the same card twice). */
    private final java.util.Set<Long> refreshQueued = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final IgdbAuthService authService;
    private final IgdbClient igdbClient;
    private final IgdbGameRepository games;
    private final IgdbMatchCacheRepository matches;
    private final Clock clock;

    @Autowired
    public IgdbGameService(IgdbAuthService authService, IgdbClient igdbClient,
                           IgdbGameRepository games, IgdbMatchCacheRepository matches) {
        this(authService, igdbClient, games, matches, Clock.systemUTC());
    }

    /** Test seam: an injected clock drives the miss-TTL verdicts. */
    IgdbGameService(IgdbAuthService authService, IgdbClient igdbClient,
                    IgdbGameRepository games, IgdbMatchCacheRepository matches, Clock clock) {
        this.authService = authService;
        this.igdbClient = igdbClient;
        this.games = games;
        this.matches = matches;
        this.clock = clock;
    }

    /**
     * Runs the ladder. Every input is optional; the controller has already validated
     * the app id shape, the lengths, and that at least one input is present.
     */
    @Transactional
    public GameLookupResponse lookup(String appId, String exeName, String folderName) {
        requireConfigured();
        if (appId != null) {
            GameCard bySteam = resolveSteam(appId);
            if (bySteam != null) {
                return GameLookupResponse.resolved(bySteam);
            }
        }
        List<GameCard> candidates = null;
        for (String candidate : IgdbNames.searchCandidates(exeName, folderName)) {
            GameLookupResponse byName = resolveByName(candidate);
            if (byName == null) {
                continue; // this candidate is a checked dead end — try the next one
            }
            if (byName.game() != null) {
                return byName; // an exact name equality beats any candidate list
            }
            if (candidates == null) {
                candidates = new ArrayList<>(byName.candidates());
            } else {
                for (GameCard card : byName.candidates()) { // plain loop: `candidates` is not final
                    if (candidates.size() < SEARCH_LIMIT) {
                        candidates.add(card);
                    }
                }
            }
        }
        return candidates == null ? GameLookupResponse.empty()
                : GameLookupResponse.candidates(candidates);
    }

    /** Serves a card by IGDB id — the cache first, IGDB once when never seen. */
    @Transactional
    public GameCard cardOf(long igdbId) {
        requireConfigured();
        IgdbGame cached = games.findById(igdbId).orElse(null);
        if (cached != null) {
            return GameCard.from(cached);
        }
        JsonNode found = igdbClient.query("games",
                CARD_FIELDS + " where id = " + igdbId + "; limit 1;");
        if (!found.isArray() || found.isEmpty()) {
            return null;
        }
        return GameCard.from(saveCard(found.get(0)));
    }

    /**
     * Pins a user's pick from an ambiguous lookup: the passed app id / exe name /
     * folder name resolve to the SAME match keys the ladder reads, and each is
     * recorded as a permanent hit on the chosen game.
     */
    @Transactional
    public void confirm(String appId, String exeName, String folderName, long igdbId) {
        requireConfigured();
        if (!games.existsById(igdbId)) {
            throw new IllegalArgumentException("Unknown game — lookup it first");
        }
        Instant now = now();
        if (appId != null) {
            matches.save(new IgdbMatchCache(IgdbNames.steamKey(appId), igdbId, now));
        }
        for (String candidate : IgdbNames.searchCandidates(exeName, folderName)) {
            matches.save(new IgdbMatchCache(IgdbNames.nameKey(candidate), igdbId, now));
        }
        log.info("pinned IGDB game {} to the match keys of the current lookup", igdbId);
    }

    // --- rung 1: the exact Steam-appid route ---

    private GameCard resolveSteam(String appId) {
        String key = IgdbNames.steamKey(appId);
        IgdbMatchCache cached = matches.findById(key).orElse(null);
        if (cached != null) {
            if (cached.getIgdbGameIgdbId() != null) {
                return card(cached.getIgdbGameIgdbId());
            }
            if (isFresh(cached)) {
                return null; // checked recently — nothing on Steam with this appid
            }
        }
        JsonNode found = igdbClient.query("games", CARD_FIELDS
                + " where external_games.external_game_source = 1 & external_games.uid = \""
                + appId + "\"; limit 1;"); // appId is ^[0-9]{1,12}$ — nothing to escape
        if (found.isArray() && !found.isEmpty()) {
            IgdbGame saved = saveCard(found.get(0));
            matches.save(new IgdbMatchCache(key, saved.getIgdbId(), now()));
            return GameCard.from(saved);
        }
        matches.save(new IgdbMatchCache(key, null, now()));
        return null;
    }

    // --- rung 2: the fuzzy-search route ---

    private GameLookupResponse resolveByName(String candidate) {
        String key = IgdbNames.nameKey(candidate);
        IgdbMatchCache cached = matches.findById(key).orElse(null);
        if (cached != null) {
            if (cached.getIgdbGameIgdbId() != null) {
                return GameLookupResponse.resolved(card(cached.getIgdbGameIgdbId()));
            }
            if (isFresh(cached)) {
                return null; // this title searched empty recently — dead end for now
            }
        }
        JsonNode results = igdbClient.query("games", "search \"" + candidate + "\"; "
                + CARD_FIELDS + " where version_parent = null; limit " + SEARCH_LIMIT + ";");
        if (!results.isArray() || results.isEmpty()) {
            matches.save(new IgdbMatchCache(key, null, now()));
            return null;
        }
        List<GameCard> found = new ArrayList<>();
        for (JsonNode node : results) {
            found.add(GameCard.from(saveCard(node)));
        }
        for (GameCard card : found) {
            if (candidate.equals(IgdbNames.normalizeTitle(card.name()))) {
                matches.save(new IgdbMatchCache(key, card.igdbId(), now()));
                return GameLookupResponse.resolved(card); // exact equality — confident
            }
        }
        return GameLookupResponse.candidates(found); // close, not equal — the user decides
    }

    // --- helpers ---

    private void requireConfigured() {
        if (!authService.integrationActive()) {
            throw new IgdbNotConfiguredException();
        }
    }

    private GameCard card(long igdbId) {
        IgdbGame game = games.findById(igdbId).orElse(null);
        // A hit always points at a stored card (both are written together); a dangling
        // reference would mean a wiped igdb_games — degrade to "not found" then.
        if (game == null) {
            return null;
        }
        maybeQueueCardRefresh(game);
        return GameCard.from(game);
    }

    /** The lazy card refresh: when a CACHE-HIT lookup touches a card older than
     *  {@link #CARD_REFRESH_TTL}, the answer still comes from the cache at once —
     *  the re-fetch (cover/genres/name drift on IGDB) runs on the background
     *  lane and silently updates the row. Failures just wait for the next day. */
    void maybeQueueCardRefresh(IgdbGame game) {
        // MANUAL cards (negative ids, the phase-3 round) have no IGDB twin to
        // re-fetch — the lazy refresh is for real catalogue rows only.
        if (game.getIgdbId() <= 0
                || Duration.between(game.getCachedAt(), now()).compareTo(CARD_REFRESH_TTL) < 0
                || !refreshQueued.add(game.getIgdbId())) {
            return;
        }
        long igdbId = game.getIgdbId();
        refreshLane.execute(() -> {
            try {
                JsonNode fresh = igdbClient.query("games",
                        CARD_FIELDS + " where id = " + igdbId + "; limit 1;");
                if (fresh.isArray() && !fresh.isEmpty()) {
                    saveCard(fresh.get(0)); // also stamps cachedAt — the TTL restarts
                    log.info("IGDB card #{} lazily refreshed", igdbId);
                }
            } catch (Exception failure) {
                log.info("IGDB card #{} refresh failed ({}), retrying no sooner than the next day",
                        igdbId, failure.getMessage());
            } finally {
                refreshQueued.remove(igdbId);
            }
        });
    }

    private boolean isFresh(IgdbMatchCache entry) {
        return now().isBefore(entry.getResolvedAt().plus(MISS_TTL));
    }

    private Instant now() {
        return Instant.now(clock);
    }

    /** Upserts one /v4/games node into the card cache (a stable card — written once). */
    private IgdbGame saveCard(JsonNode node) {
        long igdbId = node.path("id").asLong();
        if (igdbId <= 0) {
            throw new IgdbException("IGDB game response missing id");
        }
        String name = node.path("name").asText("");
        if (name.isBlank()) {
            throw new IgdbException("IGDB game " + igdbId + " response missing name");
        }
        JsonNode cover = node.path("cover");
        String coverImageId = cover.isObject() ? cover.path("image_id").asText(null) : null;
        Long firstReleaseDate = node.hasNonNull("first_release_date")
                ? node.path("first_release_date").asLong() : null;
        String genresJson = GameCard.genresToJson(node.path("genres"));
        IgdbGame game = games.findById(igdbId).orElse(null);
        if (game == null) {
            game = new IgdbGame(igdbId, name, coverImageId, genresJson, firstReleaseDate, now());
        } else {
            game.setName(name);
            game.setCoverImageId(coverImageId);
            game.setGenres(genresJson);
            game.setFirstReleaseDate(firstReleaseDate);
        }
        return games.save(game);
    }
}
