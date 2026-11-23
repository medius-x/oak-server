package oak.server.service.sgdb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import oak.server.service.igdb.IgdbException;
import oak.server.service.igdb.UpstreamCircuitBreaker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The single outbound door to SteamGridDB's public API v2 (2026-11-19): square
 * game ICONS, which IGDB simply has none of. Only the DOCUMENTED platform route
 * is used — {@code GET /v2/icons/steam/{appid}?styles=official} answers the
 * whole official list directly by the Steam appid (no game lookup needed);
 * the unprefixed {@code /icons/{gameId}} form is an undocumented stub that
 * returns ONE arbitrary community upload (a clan "XD" logo instead of PUBG's
 * icon, live probe 2026-11-19) and ignores filters — the first "SGDB is junk"
 * verdict stood on it until the official node client (github.com/SteamGridDB/
 * node-steamgriddb) corrected the routes.
 *
 * <p>The key rides the {@code Authorization: Bearer} header, never a URL. The
 * {@link UpstreamCircuitBreaker} in front mirrors {@code IgdbClient}: a
 * blocked/unreachable host must not burn timeouts on every game — see its
 * javadoc for the policy. {@code styles=official} is the quality gate: the
 * junk uploads are {@code custom}-styled and never requested at all.
 *
 * <p><b>Transport (2026-11-24, the silent-wedge post-mortem).</b> Runs on
 * {@link HttpClient} with a HARD total budget per request
 * ({@code sendAsync + get(budget)}). The previous HttpURLConnection-based
 * stack cannot bound a request's total duration: its read timeout only fires
 * on SILENCE between packets, so a trickling CDN (a packet every few seconds)
 * keeps the socket alive forever while delivering nothing — observed live as
 * an icon task wedged for hours with zero log lines, parking the whole
 * single-thread icon lane (2026-11-23 22:00 smoke). The JDK client's
 * {@code sendAsync} future is cancellable: on budget exhaustion
 * {@code cancel(true)} closes the underlying connection, the task fails, the
 * breaker parks, the lane moves on. Worst case per request: the budget.
 */
@Slf4j
@Service
public class SgdbClient {

    private static final String PROD_ICONS_URL = "https://www.steamgriddb.com/api/v2/icons/steam/";

    /** The hard ceiling per HTTP request (headers + body, both legs): a slow
     * -but-alive transit (~2 KB/s, the observed v4 path) still fits a real
     *  icon inside; anything wedged or trickling is cut off and counted by
     *  the breaker. Generous on purpose — this is a background lane. */
    static final Duration DEFAULT_REQUEST_BUDGET = Duration.ofSeconds(120);

    private final String iconsUrl;
    private final Duration requestBudget;
    private final HttpClient http;
    private final UpstreamCircuitBreaker breaker;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public SgdbClient() {
        this(PROD_ICONS_URL, DEFAULT_REQUEST_BUDGET,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                new UpstreamCircuitBreaker(Clock.systemUTC()));
    }

    /** Test seam: a local HttpServer's base URL, a short budget and a fresh breaker. */
    SgdbClient(String iconsUrl, Duration requestBudget, HttpClient http, UpstreamCircuitBreaker breaker) {
        this.iconsUrl = iconsUrl;
        this.requestBudget = requestBudget;
        this.http = http;
        this.breaker = breaker;
    }

    /** A settings change must heal the door at once — a parked breaker must not
     *  shadow a fresh key for minutes (the key endpoint 401s count as failures). */
    void resetBreaker() {
        breaker.onSuccess();
    }

    /** The picked icon: its origin URL plus the cached bytes (clients fetch
     *  through OUR server — the CDN is unreachable from some client machines). */
    public record SgdbIcon(String url, String mime, byte[] bytes) {
    }

    /**
     * The game's best OFFICIAL icon with its BYTES, or null when SGDB has none.
     * Pick policy: PNGs by DESCENDING width (the panel renders ~40px, but big
     * sources stay crisp), then the first ICO (SGDB metadata carries no ICO
     * dimensions, yet the files hold multiple sizes inside — Chromium renders
     * them all). Candidates download in that order and the FIRST ONE FITTING
     * the cache cap wins — Heavy Rain's 1024² is 725 KB, over the cap, and
     * refusing the whole game (the 2026-11-23 "fallback again" smoke) instead
     * of taking its 327 KB 512² was simply wrong.
     */
    public SgdbIcon bestOfficialIcon(String apiKey, String steamAppId) {
        if (breaker.isOpen()) {
            throw new IgdbException("SteamGridDB unreachable — outbound paused for "
                    + breaker.cooldownLeftMillis() / 1000 + " more s");
        }
        try {
            JsonNode answer = request(apiKey, steamAppId);
            SgdbIcon icon = pickAndDownload(answer);
            int recoveredFrom = breaker.onSuccess();
            if (recoveredFrom > 0) {
                log.info("SteamGridDB reachable again after {} failed attempt(s)", recoveredFrom);
            }
            return icon;
        } catch (IgdbException failure) {
            Long pausedForMillis = breaker.onFailure();
            if (pausedForMillis != null) {
                log.warn("SteamGridDB unreachable ({}); outbound parked for {} s — cards keep the IGDB square",
                        failure.getMessage(), pausedForMillis / 1000);
            }
            throw failure;
        }
    }

    /** The ordered candidates (PNGs widest-first, then the first ICO) walked
     *  until one fits the cache cap; the downloads share the breaker's success
     *  accounting (any result is upstream proof). */
    private SgdbIcon pickAndDownload(JsonNode answer) {
        if (answer == null || !answer.path("data").isArray()) {
            return null;
        }
        List<JsonNode> pngs = new ArrayList<>();
        String firstIcoUrl = null;
        for (JsonNode icon : answer.path("data")) {
            String url = icon.path("url").asText("");
            String mime = icon.path("mime").asText("");
            if (url.isEmpty()) {
                continue;
            }
            if (mime.contains("png")) {
                pngs.add(icon);
            } else if (firstIcoUrl == null && mime.contains("icon")) {
                firstIcoUrl = url;
            }
        }
        pngs.sort((left, right) -> Long.compare(right.path("width").asLong(0), left.path("width").asLong(0)));
        for (JsonNode png : pngs) {
            SgdbIcon icon = download(png.path("url").asText());
            if (icon != null) {
                return icon;
            } // over the cap — the warn landed, the next candidate gets its chance
        }
        return firstIcoUrl == null ? null : download(firstIcoUrl);
    }

    private JsonNode request(String apiKey, String steamAppId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(iconsUrl + steamAppId + "?styles=official"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .GET()
                .build();
        try {
            HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null; // no icons for the game — an answer, not an outage
            }
            if (response.statusCode() >= 400) {
                // 401 = the admin's key died; other 4xx/5xx = upstream trouble.
                // Both count as failures (the 404 above is the only non-trip).
                throw new IgdbException("SteamGridDB rejected the query: " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (IOException failure) {
            throw new IgdbException("SteamGridDB request failed: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IgdbException("SteamGridDB request interrupted", interrupted);
        }
    }

    /** Downloads one candidate (the PUBLIC CDN — no key; the CDN host is fixed
     *  and the path is the SGDB-served URL verbatim). The body is read as RAW
     *  BYTES straight off the JDK client — no converter machinery at all (the
     *  converter layer is what died on the CDN's real {@code image/x-icon}
     *  content type, 2026-11-23). Oversized answers are refused (null) — by
     *  the declared Content-Length first, then by the actual byte count; the
     *  caller walks on to the next candidate. The mime rides the RESPONSE
     *  header (the CDN says {@code image/x-icon}, whatever the API metadata
     *  claimed), falling back to the URL extension. */
    private SgdbIcon download(String url) {
        // The candidate line (2026-11-24): a silent gap between "resolving"
        // and this line = the API request is the wedged leg; a gap AFTER it =
        // the CDN transfer itself.
        log.info("downloading the SteamGridDB icon candidate {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new IOException("HTTP " + response.statusCode());
            }
            long declaredBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredBytes > oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES) {
                log.warn("SteamGridDB icon over the cache cap ({} B declared) — trying the next candidate: {}",
                        declaredBytes, url);
                return null;
            }
            byte[] bytes = response.body();
            if (bytes.length == 0) {
                return null;
            }
            if (bytes.length > oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES) {
                log.warn("SteamGridDB icon over the cache cap ({} B) — trying the next candidate: {}",
                        bytes.length, url);
                return null;
            }
            String mime = response.headers().firstValue("Content-Type")
                    .map(MediaType::parseMediaType)
                    .map(MediaType::toString)
                    .orElse(url.endsWith(".ico") ? "image/vnd.microsoft.icon" : "image/png");
            return new SgdbIcon(url, mime, bytes);
        } catch (IOException failure) {
            throw new IgdbException("SteamGridDB icon download failed: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IgdbException("SteamGridDB icon download interrupted", interrupted);
        }
    }

    /** The hard-budget send: {@code sendAsync} keeps the lane thread free to
     *  walk away — {@code get(budget)} bounds headers AND body, and a timeout
     *  cancels the future, which closes the underlying connection (the whole
     *  point of the 2026-11-24 migration: a wedged read must die, not outlive
     *  the queue). */
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> inFlight = http.sendAsync(request, bodyHandler);
        try {
            return inFlight.get(requestBudget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException budgetExceeded) {
            inFlight.cancel(true);
            throw new IOException("exceeded the " + requestBudget.toSeconds() + " s budget (wedged or trickling upstream)");
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause);
        }
    }
}
