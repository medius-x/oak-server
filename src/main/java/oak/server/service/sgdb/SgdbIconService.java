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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The icon layer over the cached game cards (V23, 2026-11-19): asks SteamGridDB
 * for the game's official square icon and stores the picked URL on the card
 * forever. Deliberately a BACKGROUND lane — a lookup's own answer must never
 * wait on a second upstream (the card ships without the icon, and the client's
 * re-fetch picks it up); and deliberately ONE daemon thread with an in-flight
 * set — icon fetches are rare (once per game) and must not stampede.
 *
 * <p>Delivery is RETRIED hourly for undelivered icons (2026-11-24): the CDN
 * transit from some hostings swings between ~2 KB/s and a strangled ~325 B/s,
 * so a single dogleg-moment attempt is a lottery — the sweep buys every game
 * its window eventually. Honest misses (SGDB answered "no official icon")
 * carry the V25 marker and are never re-asked.
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

    /** The lane's watch (2026-11-24, the silent-queue smoke): a wedged task
     *  must not become silence — JVM DNS resolution has NO timeout at all, and
     *  a slow-but-legit download may run up to the download budget (15 min);
     *  on ONE lane thread either parks the whole queue invisibly (a whole
     *  evening with zero SGDB log lines, 2026-11-23 22:00). Warns only PAST
     *  the budget's worst case, so a legitimate slow crawl never trips it. */
    static final long LANE_STUCK_AFTER_MILLIS = 20 * 60_000L;

    private volatile long laneBusySinceMillis = 0L;
    private volatile long laneBusyGameId = 0L;

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
     *  the game carries one). No-op for manual cards, resolved cards, ANSWERED
     *  misses (the V25 marker) and games no Steam key ever pointed at. */
    public void queueBackfill(long igdbId) {
        if (igdbId <= 0) {
            return; // manual cards have no IGDB identity and no Steam appid
        }
        Optional<IgdbGame> game = games.findById(igdbId);
        if (game.isEmpty() || game.get().getSgdbIconBytes() != null
                || Boolean.TRUE.equals(game.get().getSgdbIconMissing())) {
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
        log.info("queued a SteamGridDB icon resolution for game {} (appid {})", igdbId, appId);
        iconLane.execute(() -> {
            try {
                resolve(igdbId, appId);
            } finally {
                queued.remove(igdbId);
            }
        });
    }

    private void resolve(long igdbId, String appId) {
        laneBusySinceMillis = System.currentTimeMillis();
        laneBusyGameId = igdbId;
        try {
            log.info("resolving the SteamGridDB icon for game {} (appid {})", igdbId, appId);
            Optional<IgdbGame> existing = games.findById(igdbId);
            if (existing.isEmpty() || existing.get().getSgdbIconBytes() != null
                    || Boolean.TRUE.equals(existing.get().getSgdbIconMissing())) {
                return; // delivered already (our lane or a client offer), answered miss, or the card vanished
            }
            String apiKey = apiKey();
            if (apiKey == null || isPaused(profiles.singleton())) {
                return; // switched off (or paused) between the queueing and the run
            }
            SgdbClient.Resolution outcome;
            try {
                IgdbGame game = existing.get();
                outcome = client.resolveIcon(apiKey, appId, game.getSgdbIconUrl(), game.getSgdbIconPartial());
            } catch (IgdbException upstream) {
                // Honest per-attempt logging (2026-11-24): the breaker logs only
                // its PARKING transition, so its first two consecutive failures
                // used to vanish — a whole evening of budget deaths with zero
                // lines (the 22:47 smoke). The sweep retries later regardless.
                log.warn("SteamGridDB icon attempt for game {} (appid {}) failed: {} — will retry later",
                        igdbId, appId, upstream.getMessage());
                return;
            }
            if (outcome.icon() != null) {
                SgdbClient.SgdbIcon icon = outcome.icon();
                int stored = games.storeIconIfAbsent(igdbId, icon.url(), icon.mime(), icon.bytes());
                if (stored > 0) {
                    log.info("game {} got its SteamGridDB icon (appid {}, {} B)", igdbId, appId, icon.bytes().length);
                } else {
                    // a client offer won the race mid-attempt — its bytes stand
                    log.info("game {} icon already delivered by another source — dropping our copy", igdbId);
                }
                return;
            }
            if (outcome.partialBytes() != null) {
                games.storePartialIfUndelivered(igdbId, outcome.partialUrl(), outcome.partialBytes());
                log.info("game {} icon in transit: {} B accumulated (appid {}) — the next attempt continues",
                        igdbId, outcome.partialBytes().length, appId);
                return;
            }
            IgdbGame game = existing.get();
            game.setSgdbIconMissing(true); // the V25 marker: an ANSWER, never re-asked
            games.save(game);
            log.info("no SteamGridDB icon for game {} (appid {}) — keeping the IGDB square", igdbId, appId);
        } finally {
            laneBusySinceMillis = 0L;
            laneBusyGameId = 0L;
        }
    }

    /** Accepts a CLIENT's fetched icon bytes (2026-11-24): on hostings whose
     *  transit strangles the CDN, a healthy-networked member's machine often
     *  delivers first. The bytes are UNTRUSTED input — magic-byte sniffing
     *  decides the mime (never the client's claim), PNG and ICO only (no SVG:
     *  scripts), and the size cap stands. The store itself is the atomic
     *  first-wins UPDATE, so our own lane and racing clients settle without
     *  coordination. Returns true when THIS offer became the game's icon. */
    public boolean acceptClientIcon(long igdbId, byte[] bytes) {
        String mime = sniffIconMime(bytes);
        if (mime == null) {
            return false;
        }
        IgdbGame game = games.findById(igdbId).orElse(null);
        if (game == null || game.getSgdbIconBytes() != null) {
            return false; // unknown game, or an icon already stands
        }
        String provenanceUrl = game.getSgdbIconUrl(); // the pending URL the client fetched
        return games.storeIconIfAbsent(igdbId, provenanceUrl, mime, bytes) > 0;
    }

    /** PNG/ICO detection by magic bytes; null = anything else (refused). */
    public static String sniffIconMime(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return null;
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 1 && bytes[3] == 0) {
            return "image/x-icon";
        }
        return null;
    }

    /** The hourly catch-up sweep (2026-11-24, the throttled-transit
     *  post-mortem): the hosting's route to the SGDB CDN swings between
     *  ~2 KB/s and ~325 B/s (measured on the same URL a day apart) — client
     *  doglegs sample only moments of that. Re-queue every undelivered icon
     *  hourly; while the outbound is parked the whole sweep is skipped for
     *  one log line, and the first breathable window delivers the bytes into
     *  the cache forever. */
    @Scheduled(fixedDelay = 3_600_000L)
    void sweepUndeliveredIcons() {
        if (client.parked()) {
            log.info("icon sweep skipped — SteamGridDB outbound parked ({} s left)",
                    client.parkedForSeconds());
            return;
        }
        int requeued = 0;
        for (IgdbGame game : games.findBySgdbIconBytesIsNull()) {
            if (Boolean.TRUE.equals(game.getSgdbIconMissing())) {
                continue; // an answered miss — nothing to deliver, ever
            }
            queueBackfill(game.getIgdbId()); // dedupes and drops no-steam-key games inside
            requeued += 1;
        }
        if (requeued > 0) {
            log.info("icon sweep: re-queued {} undelivered icon(s)", requeued);
        }
    }

    /** The watch's tick: null while idle/fresh, a WARN line once a task has
     *  held the lane past {@link #LANE_STUCK_AFTER_MILLIS}. Pure so the test
     *  drives the clock. */
    @Scheduled(fixedDelay = 60_000L)
    void reportStuckLane() {
        long busySinceMillis = laneBusySinceMillis;
        long gameId = laneBusyGameId;
        if (busySinceMillis == 0L || gameId <= 0L) {
            return; // idle — nothing to complain about
        }
        String line = stuckLaneLine(busySinceMillis, System.currentTimeMillis(), gameId);
        if (line != null) {
            log.warn(line);
        }
    }

    /** The verdict for {@link #reportStuckLane()}: null = still within budget. */
    static String stuckLaneLine(long busySinceMillis, long nowMillis, long gameId) {
        long busyForMillis = nowMillis - busySinceMillis;
        if (busyForMillis < LANE_STUCK_AFTER_MILLIS) {
            return null;
        }
        return "SteamGridDB icon lane busy for " + (busyForMillis / 1000) + " s on game " + gameId
                + " — likely a wedged DNS/socket read; the queue is parked behind it";
    }

    private String apiKey() {
        ServerProfile profile = profiles.singleton();
        return profile == null ? null : profile.getSgdbApiKey();
    }
}
