package oak.server.service.sgdb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import oak.server.service.igdb.IgdbException;
import oak.server.service.igdb.UpstreamCircuitBreaker;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;

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
 */
@Slf4j
@Service
public class SgdbClient {

    private static final String ICONS_URL = "https://www.steamgriddb.com/api/v2/icons/steam/";

    private final RestClient restClient;
    private final UpstreamCircuitBreaker breaker;

    @Autowired
    public SgdbClient() {
        this(defaultClient(), new UpstreamCircuitBreaker(Clock.systemUTC()));
    }

    /** Test seam: a RestClient bound to a MockRestServiceServer plus its breaker. */
    SgdbClient(RestClient restClient, UpstreamCircuitBreaker breaker) {
        this.restClient = restClient;
        this.breaker = breaker;
    }

    private static RestClient defaultClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        // 60 s (was 8 via the 2026-11-23 slow-transit post-mortem): the icon
        // BYTES ride a ~20 KB/s v4 transit on some hostings — a near-cap
        // 1 MB icon drips for ~55 s. The read timeout only fires on SILENCE
        // between packets, and this client serves the BACKGROUND icon lane
        // (nobody awaits the answer synchronously), so a long tail costs
        // nothing but queue latency. Contrast IgdbCoverService: its endpoint
        // is a synchronous client wait and stays at 20 s.
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory).build();
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
            SgdbIcon icon = pickAndDownload(apiKey, answer);
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
    private SgdbIcon pickAndDownload(String apiKey, JsonNode answer) {
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
        try {
            return restClient.get()
                    .uri(ICONS_URL + steamAppId + "?styles=official")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException failure) {
            // 401 = the admin's key died; 404 = no icons for the game — both are
            // answers, not outages; only the transport layer trips the breaker.
            if (failure.getStatusCode().value() == 404) {
                return null;
            }
            throw new IgdbException("SteamGridDB rejected the query: "
                    + failure.getStatusCode().value(), failure);
        } catch (RestClientException failure) {
            throw new IgdbException("SteamGridDB request failed: " + failure.getMessage(), failure);
        }
    }

    /** Downloads one candidate (the PUBLIC CDN — no key; the CDN host is fixed
     *  and the path is the SGDB-served URL verbatim). The body is read as a
     *  RAW STREAM through {@code exchange} — deliberately past every
     *  {@code HttpMessageConverter}: the test server's live run (2026-11-23)
     *  died inside the converter machinery on the CDN's real
     *  {@code image/x-icon} content type ("Error while extracting response
     *  for type [byte[]]…"), while a locally mocked byte[] answer with the
     *  same type sails through — no converter, no converter edge cases.
     *  Oversized answers are refused (null) — first by the declared
     *  Content-Length (a fat icon is not even read), then by the actual byte
     *  count; the caller walks on to the next candidate. The mime rides the
     *  RESPONSE header (the CDN says {@code image/x-icon}, whatever the API
     *  metadata claimed), falling back to the URL extension. */
    private SgdbIcon download(String url) {
        try {
            return restClient.get()
                    .uri(java.net.URI.create(url))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new java.io.IOException("HTTP " + response.getStatusCode().value());
                        }
                        long declaredBytes = response.getHeaders().getContentLength();
                        if (declaredBytes > oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES) {
                            log.warn("SteamGridDB icon over the cache cap ({} B declared) — trying the next candidate: {}",
                                    declaredBytes, url);
                            return null;
                        }
                        byte[] bytes = response.getBody().readAllBytes();
                        if (bytes.length == 0) {
                            return null;
                        }
                        if (bytes.length > oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES) {
                            log.warn("SteamGridDB icon over the cache cap ({} B) — trying the next candidate: {}",
                                    bytes.length, url);
                            return null;
                        }
                        MediaType contentType = response.getHeaders().getContentType();
                        String mime = contentType != null
                                ? contentType.toString()
                                : url.endsWith(".ico") ? "image/vnd.microsoft.icon" : "image/png";
                        return new SgdbIcon(url, mime, bytes);
                    }, true);
        } catch (RestClientException failure) {
            throw new IgdbException("SteamGridDB icon download failed: " + failure.getMessage(), failure);
        }
    }
}
