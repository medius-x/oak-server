package oak.server.service.sgdb;

import lombok.extern.slf4j.Slf4j;
import oak.server.entity.IgdbGame;
import oak.server.entity.ServerProfile;
import oak.server.repository.IgdbGameRepository;
import oak.server.repository.IgdbMatchCacheRepository;
import oak.server.repository.ServerProfileRepository;
import oak.server.service.igdb.IgdbException;
import oak.server.service.igdb.IgdbNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The icon layer over the cached game cards (V23, 2026-11-19): asks SteamGridDB
 * ONCE per game for its official square icon and stores the picked URL on the
 * card forever. Deliberately a BACKGROUND lane — a lookup's own answer must
 * never wait on a second upstream (the card ships without the icon, and the
 * client's re-fetch picks it up); and deliberately ONE daemon thread with an
 * in-flight set — icon fetches are rare (once per game) and must not stampede.
 *
 * <p>The appid comes from the match cache itself: every {@code steam:<appid>}
 * key row points at the game it resolved to, so the service can backfill any
 * card (including everything resolved before this column existed) without the
 * client ever sending an appid. Games resolved by name only (no Steam appid),
 * manual cards (negative ids) and SGDB misses simply keep {@code null} — the
 * client falls back to the IGDB cover square.
 */
@Slf4j
@Service
public class SgdbIconService {

    private final ServerProfileRepository profiles;
    private final IgdbGameRepository games;
    private final IgdbMatchCacheRepository matches;
    private final SgdbClient client;

    /** One daemon lane + dedupe: a game is asked at most once per queue lifetime. */
    private final ExecutorService iconLane = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sgdb-icon");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    @Autowired
    public SgdbIconService(ServerProfileRepository profiles, IgdbGameRepository games,
                           IgdbMatchCacheRepository matches, SgdbClient client) {
        this.profiles = profiles;
        this.games = games;
        this.matches = matches;
        this.client = client;
    }

    /** Whether the icon layer is on: a stored key AND not paused (the pause
     *  keeps the key and the cached bytes — they just stop being served). */
    public boolean active() {
        return apiKey() != null && !isPaused(profiles.singleton());
    }

    /** The pause switch position (the IGDB pattern; NULL = enabled). */
    public static boolean isPaused(ServerProfile profile) {
        return profile != null && Boolean.FALSE.equals(profile.getSgdbEnabled());
    }

    /** Configured AND not paused — the {@code sgdb-icons} capability's condition. */
    public static boolean isIntegrationActive(ServerProfile profile) {
        return profile != null && profile.getSgdbApiKey() != null && !isPaused(profile);
    }

    /** Queues the icon resolution for a game whose Steam appid is already known
     *  (the fresh-resolve path). No-op when off, resolved, manual or already queued. */
    public void queueForAppId(long igdbId, String appId) {
        queue(igdbId, appId);
    }

    /** Queues the icon resolution for a game whose appid is recovered from the
     *  match cache (the backfill path — every {@code steam:*} row pointing at
     *  the game carries one). No-op for manual cards, resolved cards and games
     *  no Steam key ever pointed at. */
    public void queueBackfill(long igdbId) {
        if (igdbId <= 0) {
            return; // manual cards have no IGDB identity and no Steam appid
        }
        Optional<IgdbGame> game = games.findById(igdbId);
        if (game.isEmpty() || game.get().getSgdbIconBytes() != null) {
            return;
        }
        List<String> steamKeys = matches.findSteamKeys(igdbId);
        if (!steamKeys.isEmpty()) {
            queue(igdbId, steamKeys.get(0).substring(IgdbNames.STEAM_KEY_PREFIX.length()));
        }
    }

    /** A settings change must heal the door at once — a parked breaker must not
     *  shadow a fresh key for minutes. */
    public void onKeyChanged() {
        client.resetBreaker();
    }

    /** The cached icon bytes for the endpoint (null = none yet). */
    public byte[] cachedIconBytes(long igdbId) {
        return games.findById(igdbId).map(IgdbGame::getSgdbIconBytes).orElse(null);
    }

    /** The cached icon's content type; {@code image/png} when unknown (defensive). */
    public String cachedIconMime(long igdbId) {
        return games.findById(igdbId).map(IgdbGame::getSgdbIconMime).filter(mime -> mime != null && !mime.isEmpty())
                .orElse("image/png");
    }

    private void queue(long igdbId, String appId) {
        if (!active() || appId == null || appId.isEmpty() || igdbId <= 0 || !queued.add(igdbId)) {
            return;
        }
        iconLane.execute(() -> {
            try {
                resolve(igdbId, appId);
            } finally {
                queued.remove(igdbId);
            }
        });
    }

    private void resolve(long igdbId, String appId) {
        Optional<IgdbGame> existing = games.findById(igdbId);
        if (existing.isEmpty() || existing.get().getSgdbIconBytes() != null) {
            return; // resolved earlier or the card vanished — nothing to do
        }
        String apiKey = apiKey();
        if (apiKey == null || isPaused(profiles.singleton())) {
            return; // switched off (or paused) between the queueing and the run
        }
        SgdbClient.SgdbIcon icon;
        try {
            icon = client.bestOfficialIcon(apiKey, appId);
        } catch (IgdbException upstream) {
            // the breaker logged the transition; the card keeps the IGDB square,
            // and the next card re-fetch re-queues (the breaker parks first)
            return;
        }
        if (icon == null) {
            log.info("no SteamGridDB icon for game {} (appid {}) — keeping the IGDB square", igdbId, appId);
            return;
        }
        IgdbGame game = existing.get();
        game.setSgdbIconUrl(icon.url());
        game.setSgdbIconMime(icon.mime());
        game.setSgdbIconBytes(icon.bytes());
        games.save(game);
        log.info("game {} got its SteamGridDB icon (appid {}, {} B)", igdbId, appId, icon.bytes().length);
    }

    private String apiKey() {
        ServerProfile profile = profiles.singleton();
        return profile == null ? null : profile.getSgdbApiKey();
    }
}
