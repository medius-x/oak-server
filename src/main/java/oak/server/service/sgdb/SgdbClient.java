package oak.server.service.sgdb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import oak.server.service.igdb.IgdbException;
import oak.server.service.igdb.UpstreamCircuitBreaker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * {@link HttpClient} with a HARD budget on every phase: {@code sendAsync +
 * get(budget)} for the headers, and a per-read stall deadline for the body —
 * the previous HttpURLConnection stack could not bound a request's total
 * duration at all (its read timeout only fires on SILENCE between packets,
 * so a trickling upstream parked the lane forever, observed live as hours of
 * zero log lines). A timed-out read CLOSES the connection, the attempt ends,
 * the breaker parks, the lane moves on.
 *
 * <p><b>Burst-resume (2026-11-24, the throttled-transit measurements).</b>
 * Some hostings' route to the CDN lets ~20 KB through per connection fast
 * and then strangles the flow to ~4 B/s (measured: 20075 B/10 s, then
 * +430 B in 110 s; a Range request at a fresh offset got 20 KB in 0.16 s —
 * the bucket is per connection). So a download is a CHAIN of short
 * connections: read until the stall deadline, keep the partial bytes,
 * reconnect with {@code Range: <offset>-}, repeat — every wait is bounded,
 * and the partial bytes survive between attempts (see
 * {@code igdb_games.sgdb_icon_partial}) so even the hourly sweep accretes.
 */
@Slf4j
@Service
public class SgdbClient {

    private static final String PROD_ICONS_URL = "https://www.steamgriddb.com/api/v2/icons/steam/";

    /** The API (JSON) leg's budget: the catalogue host is either fast (1.4 s
     *  live) or dead — no middle ground worth waiting for. */
    static final Duration API_REQUEST_BUDGET = Duration.ofSeconds(120);

    /** The response-headers budget per download connection: a live CDN
     *  answers in well under a second; headers that never come are a dead
     *  path, not a slow one. */
    static final Duration HEADERS_BUDGET = Duration.ofSeconds(60);

    /** One read may block this long with ZERO bytes before the connection is
     *  declared strangled: the observed burst arrives in ~10 s, so 25 s of
     *  pure silence means the token bucket is spent — reconnect (Range) or
     *  end the attempt with the partial bytes. */
    static final long STALL_AFTER_MILLIS = 25_000L;

    /** Connections per attempt: each strangled connection still delivers its
     *  ~20 KB burst, so 12 connections ≈ 240 KB of progress per attempt —
     *  the observed 147 KB Wardogs icon lands in ONE attempt, and fatter
     *  files accrete across the hourly sweep. */
    static final int MAX_CONNECTIONS_PER_ATTEMPT = 12;

    /** The whole attempt's wall clock — the lane must never be held hostage:
     *  bounded reads, bounded connections, bounded attempt, hourly retries. */
    static final long ATTEMPT_CAP_MILLIS = 10 * 60_000L;

    private final String iconsUrl;
    private final Duration apiRequestBudget;
    private final Duration headersBudget;
    private final long stallAfterMillis;
    private final long attemptCapMillis;
    private final HttpClient http;
    private final UpstreamCircuitBreaker breaker;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public SgdbClient() {
        this(PROD_ICONS_URL, API_REQUEST_BUDGET, HEADERS_BUDGET,
                STALL_AFTER_MILLIS, ATTEMPT_CAP_MILLIS,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                new UpstreamCircuitBreaker(Clock.systemUTC()));
    }

    /** Test seam: a local HttpServer's base URL, short deadlines and a fresh breaker. */
    SgdbClient(String iconsUrl, Duration apiRequestBudget, Duration headersBudget,
               long stallAfterMillis, long attemptCapMillis,
               HttpClient http, UpstreamCircuitBreaker breaker) {
        this.iconsUrl = iconsUrl;
        this.apiRequestBudget = apiRequestBudget;
        this.headersBudget = headersBudget;
        this.stallAfterMillis = stallAfterMillis;
        this.attemptCapMillis = attemptCapMillis;
        this.http = http;
        this.breaker = breaker;
    }

    /** A settings change must heal the door at once — a parked breaker must not
     *  shadow a fresh key for minutes (the key endpoint 401s count as failures). */
    void resetBreaker() {
        breaker.onSuccess();
    }

    /** Whether the outbound door is parked right now — the retry sweep checks
     *  this FIRST, so a dead path costs one log line instead of a wave of
     *  instant fast-fails. */
    public boolean parked() {
        return breaker.isOpen();
    }

    /** Seconds left in the current park, for the sweep's skip line. */
    long parkedForSeconds() {
        return breaker.cooldownLeftMillis() / 1000;
    }

    /** The picked icon: its origin URL plus the cached bytes (clients fetch
     *  through OUR server — the CDN is unreachable from some client machines). */
    public record SgdbIcon(String url, String mime, byte[] bytes) {
    }

    /** One resolution attempt's outcome. Exactly one of: DONE (the icon),
     *  PARTIAL (bytes accumulated so far for the URL — the transit strangled
     *  the transfer; the next attempt Range-resumes from them) or MISS (SGDB
     *  answered "no official icon"). A transport failure THROWS instead — it
     *  counts against the breaker. */
    public record Resolution(SgdbIcon icon, String partialUrl, byte[] partialBytes) {
        public static Resolution miss() {
            return new Resolution(null, null, null);
        }

        public static Resolution done(SgdbIcon icon) {
            return new Resolution(icon, null, null);
        }

        public static Resolution partial(String url, byte[] bytes) {
            return new Resolution(null, url, bytes);
        }
    }

    /**
     * Resolves the game's best OFFICIAL icon. Pick policy (user decision
     * 2026-11-24, the throttled-transit post-mortem): PNGs by ASCENDING width
     * — the panel renders ~40px, where a small source is visually identical
     * to a big one, while its bytes fit a strangled transit that would never
     * deliver a 725 KB "crisp" source; the first ICO follows (SGDB metadata
     * carries no ICO dimensions, yet the files hold multiple sizes inside —
     * Chromium renders them all). An over-cap candidate walks on to the next
     * instead of refusing the whole game (the 2026-11-23 "fallback again"
     * smoke). When {@code resumeUrl} is among the candidates, THAT one
     * continues from {@code resumeBytes} — a partial from an earlier attempt.
     */
    public Resolution resolveIcon(String apiKey, String steamAppId, String resumeUrl, byte[] resumeBytes) {
        return resolveIcon(apiKey, steamAppId, resumeUrl, resumeBytes, pickedUrl -> { });
    }

    /** The full form: {@code onTargetPicked} fires the moment this attempt's
     *  target URL is chosen — BEFORE the burst chain starts — so the caller
     *  can publish the pending URL and a healthy client may offer the bytes
     *  while our own download is still dripping (2026-11-24 smoke: a URL
     *  written only at attempt-end left the client nothing to fetch). */
    public Resolution resolveIcon(String apiKey, String steamAppId, String resumeUrl, byte[] resumeBytes,
                                  java.util.function.Consumer<String> onTargetPicked) {
        if (breaker.isOpen()) {
            throw new IgdbException("SteamGridDB unreachable — outbound paused for "
                    + breaker.cooldownLeftMillis() / 1000 + " more s");
        }
        try {
            Resolution outcome = attempt(apiKey, steamAppId, resumeUrl, resumeBytes, onTargetPicked);
            int recoveredFrom = breaker.onSuccess();
            if (recoveredFrom > 0) {
                log.info("SteamGridDB reachable again after {} failed attempt(s)", recoveredFrom);
            }
            return outcome;
        } catch (IgdbException failure) {
            Long pausedForMillis = breaker.onFailure();
            if (pausedForMillis != null) {
                log.warn("SteamGridDB unreachable ({}); outbound parked for {} s — cards keep the IGDB square",
                        failure.getMessage(), pausedForMillis / 1000);
            }
            throw failure;
        }
    }

    private Resolution attempt(String apiKey, String steamAppId, String resumeUrl, byte[] resumeBytes,
                               java.util.function.Consumer<String> onTargetPicked) {
        JsonNode answer = request(apiKey, steamAppId);
        List<String> candidates = orderedCandidates(answer);
        if (candidates.isEmpty()) {
            return Resolution.miss();
        }
        int startIndex = resumeUrl != null ? candidates.indexOf(resumeUrl) : -1;
        if (startIndex < 0) {
            resumeBytes = null; // the list changed under us — start the pick anew
        }
        for (int candidateIndex = Math.max(startIndex, 0); candidateIndex < candidates.size(); candidateIndex++) {
            String url = candidates.get(candidateIndex);
            byte[] startBytes = candidateIndex == startIndex && resumeBytes != null ? resumeBytes : null;
            if (candidateIndex == Math.max(startIndex, 0)) {
                onTargetPicked.accept(url); // publish before the first byte moves
            }
            Resolution outcome = downloadChain(url, startBytes);
            if (outcome != null) {
                return outcome; // DONE, or PARTIAL progress worth keeping
            } // over-cap candidate — the warn landed, walk to the next
        }
        return Resolution.miss(); // every candidate over the cap: honest miss
    }

    /** The ordered candidates (PNGs smallest-first, then the first ICO). */
    private List<String> orderedCandidates(JsonNode answer) {
        List<JsonNode> pngs = new ArrayList<>();
        String firstIcoUrl = null;
        if (answer != null && answer.path("data").isArray()) {
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
        }
        pngs.sort((left, right) -> Long.compare(left.path("width").asLong(0), right.path("width").asLong(0)));
        List<String> ordered = new ArrayList<>();
        pngs.forEach(png -> ordered.add(png.path("url").asText()));
        if (firstIcoUrl != null) {
            ordered.add(firstIcoUrl);
        }
        return ordered;
    }

    /** The burst chain for ONE candidate: read until strangled, keep the
     *  partial, reconnect with Range, repeat — until EOF (DONE, null-and-log
     *  on over-cap), the attempt cap, or the connection cap (PARTIAL). Never
     *  blocks forever: every read has a stall deadline, every phase a budget. */
    private Resolution downloadChain(String url, byte[] resumeBytes) {
        long attemptDeadline = System.currentTimeMillis() + attemptCapMillis;
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        if (resumeBytes != null && resumeBytes.length > 0) {
            received.writeBytes(resumeBytes);
        }
        for (int connection = 0; connection < MAX_CONNECTIONS_PER_ATTEMPT; connection++) {
            if (System.currentTimeMillis() >= attemptDeadline) {
                break;
            }
            long offset = received.size();
            HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(url)).GET();
            if (offset > 0) {
                request.header("Range", "bytes=" + offset + "-");
            }
            try {
                HttpResponse<InputStream> response = send(request.build(),
                        HttpResponse.BodyHandlers.ofInputStream(),
                        Math.min(headersBudget.toMillis(),
                                Math.max(1_000L, attemptDeadline - System.currentTimeMillis())));
                if (response.statusCode() >= 400) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                if (offset > 0 && response.statusCode() != 206) {
                    // the server ignored Range and restarts from zero — accept its truth
                    received.reset();
                    offset = 0;
                }
                long declaredBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declaredBytes >= 0 && declaredBytes + offset > oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES) {
                    log.warn("SteamGridDB icon over the cache cap ({} B total) — trying the next candidate: {}",
                            declaredBytes + offset, url);
                    return null;
                }
                // the file's full size as THIS response declares it (offset + remainder)
                long expectedTotal = declaredBytes >= 0 ? declaredBytes + offset : -1;
                if (!readUntilStrangled(response.body(), received)) {
                    continue; // strangled mid-transfer — reconnect (Range) for the next burst
                }
                byte[] bytes = received.toByteArray();
                if (bytes.length == 0) {
                    return null; // an empty answer — the next candidate gets its chance
                }
                if (bytes.length > oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES) {
                    log.warn("SteamGridDB icon over the cache cap ({} B) — trying the next candidate: {}",
                            bytes.length, url);
                    return null;
                }
                if (expectedTotal >= 0 && bytes.length < expectedTotal) {
                    // a strangled connection often CLOSES early, which reads as EOF —
                    // only the declared length proves completeness, never the stream's end
                    continue;
                }
                String mime = response.headers().firstValue("Content-Type")
                        .map(MediaType::parseMediaType)
                        .map(MediaType::toString)
                        .orElse(url.endsWith(".ico") ? "image/vnd.microsoft.icon" : "image/png");
                return Resolution.done(new SgdbIcon(url, mime, bytes));
            } catch (IOException failure) {
                if (received.size() > 0) {
                    // the transit gave us something before breaking — keep it,
                    // the next attempt continues from the offset
                    return Resolution.partial(url, received.toByteArray());
                }
                throw new IgdbException("SteamGridDB icon download failed: " + failure.getMessage(), failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IgdbException("SteamGridDB icon download interrupted", interrupted);
            }
        }
        if (received.size() == 0) {
            // not even one byte across every connection — the path is dead, the
            // breaker must see a failure, not a "partial" of nothing
            throw new IgdbException("SteamGridDB icon download made no progress");
        }
        return Resolution.partial(url, received.toByteArray());
    }

    /** Reads the body into {@code target} with a per-read stall deadline.
     *  Returns false when the transfer was strangled short of EOF (the
     *  partial stays in {@code target}); true at clean EOF or cap exit. */
    private boolean readUntilStrangled(InputStream body, ByteArrayOutputStream target) {
        byte[] buffer = new byte[16 * 1024];
        while (target.size() < oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES) {
            int read;
            try {
                read = readWithStall(body, buffer);
            } catch (IOException strangled) {
                return false; // silence on the wire — keep the partial, let the chain reconnect
            }
            if (read < 0) {
                return true; // EOF — the transfer completed
            }
            if (read > 0) {
                target.write(buffer, 0, read);
            }
        }
        return true; // over the cap mid-read — the caller's size check refuses it
    }

    /** One bounded read: a byte count, -1 at EOF; an IOException when no byte
     *  arrived within the stall deadline (the connection is closed — the
     *  blocked pool reader unblocks and the partial bytes stay ours). */
    private int readWithStall(InputStream stream, byte[] buffer) throws IOException {
        CompletableFuture<Integer> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return stream.read(buffer);
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        });
        try {
            return pending.get(stallAfterMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException stalled) {
            pending.cancel(true);
            closeQuietly(stream); // unblocks the pooled reader stuck in read()
            throw new IOException("stalled: no bytes for " + stallAfterMillis + " ms");
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeQuietly(stream);
            throw new IOException("interrupted");
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // already broken — the point was to unblock the reader
        }
    }

    private JsonNode request(String apiKey, String steamAppId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(iconsUrl + steamAppId + "?styles=official"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .GET()
                .build();
        try {
            HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(), apiRequestBudget.toMillis());
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

    /** The hard-budget send: {@code sendAsync} keeps the lane thread free to
     *  walk away — {@code get(budget)} bounds the phase, and a timeout cancels
     *  the future, which closes the underlying connection (a wedged phase
     *  must die, not outlive the queue). */
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler, long budgetMillis)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> inFlight = http.sendAsync(request, bodyHandler);
        try {
            return inFlight.get(budgetMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException budgetExceeded) {
            inFlight.cancel(true);
            throw new IOException("exceeded the " + budgetMillis / 1000 + " s budget (wedged or trickling upstream)");
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause);
        }
    }
}
