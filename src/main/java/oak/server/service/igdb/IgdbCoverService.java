package oak.server.service.igdb;

import lombok.extern.slf4j.Slf4j;
import oak.server.entity.IgdbGame;
import oak.server.repository.IgdbGameRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;

/**
 * The IGDB cover-THUMBNAIL proxy (V23, 2026-11-23): the square {@code t_thumb}
 * picture — the panel's fallback when a game has no SGDB icon — is fetched
 * ONCE from {@code images.igdb.com} and cached as bytes, because that CDN is
 * unreachable from some CLIENT machines exactly like SGDB's (DPI/local proxy);
 * clients get it from {@code GET /api/games/{id}/cover} and never leave home.
 *
 * <p>Fetch-on-miss (synchronous): the thumbnail is ~10–20 KB and one round
 * trip per game EVER, so the first request pays ~200 ms and the rest serve
 * from the row. The {@link UpstreamCircuitBreaker} keeps a blocked/unreachable
 * CDN from taxing every request: parked → the cached answer still serves, a
 * miss answers null (no picture until the door reopens).
 */
@Slf4j
@Service
public class IgdbCoverService {

    private static final String THUMB_URL = "https://images.igdb.com/igdb/image/upload/t_thumb/";

    private final IgdbGameRepository games;
    private final RestClient restClient;
    private final UpstreamCircuitBreaker breaker;

    @Autowired
    public IgdbCoverService(IgdbGameRepository games) {
        this(games, defaultClient(), new UpstreamCircuitBreaker(Clock.systemUTC()));
    }

    /** Test seam: a RestClient bound to a MockRestServiceServer plus its breaker. */
    IgdbCoverService(IgdbGameRepository games, RestClient restClient, UpstreamCircuitBreaker breaker) {
        this.games = games;
        this.restClient = restClient;
        this.breaker = breaker;
    }

    /** The card's cached cover bytes, fetching them on the first miss. Null =
     *  the game has no cover, or the CDN is parked/unreachable right now. */
    public byte[] coverThumb(long igdbId) {
        IgdbGame game = games.findById(igdbId).orElse(null);
        if (game == null || game.getCoverImageId() == null) {
            return null;
        }
        if (game.getCoverThumbBytes() != null) {
            return game.getCoverThumbBytes();
        }
        if (breaker.isOpen()) {
            return null; // quiet — the cached-only regime while the CDN is unreachable
        }
        try {
            byte[] bytes = restClient.get()
                    .uri(THUMB_URL + game.getCoverImageId() + ".jpg")
                    .retrieve()
                    .body(byte[].class);
            int recoveredFrom = breaker.onSuccess();
            if (recoveredFrom > 0) {
                log.info("IGDB CDN reachable again after {} failed attempt(s)", recoveredFrom);
            }
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            if (bytes.length > IgdbGame.SGDB_ICON_MAX_BYTES) {
                log.warn("IGDB cover thumbnail over the cache cap ({} B) — refused", bytes.length);
                return null;
            }
            game.setCoverThumbBytes(bytes);
            game.setCoverThumbMime("image/jpeg");
            games.save(game);
            return bytes;
        } catch (RestClientException failure) {
            Long pausedForMillis = breaker.onFailure();
            if (pausedForMillis != null) {
                log.warn("IGDB CDN unreachable ({}); outbound parked for {} s — cached covers keep serving",
                        failure.getMessage(), pausedForMillis / 1000);
            }
            return null;
        }
    }

    private static RestClient defaultClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(factory).build();
    }
}
