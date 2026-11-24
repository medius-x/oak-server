package oak.server.service.sgdb;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import oak.server.service.igdb.IgdbException;
import oak.server.service.igdb.UpstreamCircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SGDB outbound door against a REAL localhost HTTP server (the JDK's
 * built-in {@code com.sun.net.httpserver}) — the 2026-11-24 transport
 * migration retired RestClient/MockRestServiceServer, and a socket-level
 * server is the honest seam for a client whose whole point is transport
 * behaviour: the DOCUMENTED platform route with the Bearer key, the
 * smallest-first pick policy, the cache-cap walk, the honest-miss answer,
 * the shared breaker parking — and the BURST-RESUME chain: a connection
 * that strangles mid-file (the measured ~20 KB-then-silence transit) must
 * yield its partial bytes, and the next attempt must Range-resume from
 * them to completion. Nothing may hang: every phase has a deadline.
 */
class SgdbClientTest {

    private HttpServer server;
    private ExecutorService serverLane;
    private String iconsUrl;
    private SgdbClient client;

    /** path → handler; the root router dispatches, unknown paths 404. */
    private final Map<String, HttpHandler> routes = new ConcurrentHashMap<>();
    private final AtomicInteger unknownHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverLane = Executors.newCachedThreadPool(worker -> {
            Thread thread = new Thread(worker, "test-sgdb-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverLane);
        server.createContext("/", exchange -> {
            HttpHandler handler = routes.get(exchange.getRequestURI().getPath());
            if (handler == null) {
                unknownHits.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            handler.handle(exchange);
        });
        server.start();
        // The trailing slash mirrors the production constant's shape — the
        // client concatenates iconsUrl + appId verbatim.
        iconsUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/icons/steam/";
        client = newClient(Duration.ofSeconds(5), 5_000L);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverLane.shutdownNow();
    }

    /** The production-shaped seam, only the deadlines shortened. */
    private SgdbClient newClient(Duration apiBudget, long stallAfterMillis) {
        return new SgdbClient(iconsUrl, apiBudget, Duration.ofSeconds(5),
                stallAfterMillis, 30_000L,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new UpstreamCircuitBreaker(Clock.systemUTC()));
    }

    /** Writes the canned answer NOW (headers + body) — the response half of
     *  every route and inline assertion below. */
    private static void respondNow(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** A canned bytes answer with its content type — the CDN's shape. */
    private void serveBytes(String path, int status, String contentType, byte[] body) {
        routes.put(path, exchange -> respondNow(exchange, status, contentType, body));
    }

    /** A canned JSON answer — the API's shape. */
    private void serveJson(String path, int status, String json) {
        serveBytes(path, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** The Wardogs appid's API path (route + query as the client builds it). */
    private String apiPath() {
        return "/api/v2/icons/steam/578080";
    }

    private static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Test
    void queriesTheDocumentedPlatformRouteWithTheBearerKey() {
        AtomicInteger hits = new AtomicInteger();
        routes.put(apiPath(), exchange -> {
            hits.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("Bearer k1", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("styles=official", exchange.getRequestURI().getQuery());
            respondNow(exchange, 200, "application/json",
                    "{\"success\":true,\"data\":[]}".getBytes(StandardCharsets.UTF_8));
        });

        assertNull(client.resolveIcon("k1", "578080", null, null).icon());
        assertEquals(1, hits.get());
        assertEquals(0, unknownHits.get()); // the URL shape is exactly the platform route
    }

    @Test
    void theSmallestOfficialPngWinsAndTheFatOneIsNeverTouched() {
        serveJson(apiPath(), 200, """
                {"success":true,"data":[
                  {"mime":"image/vnd.microsoft.icon","width":0,"height":0,
                   "url":"%sa.ico"},
                  {"mime":"image/png","width":256,"height":256,
                   "url":"%s256.png"},
                  {"mime":"image/png","width":1024,"height":1024,
                   "url":"%s1024.png"}
                ]}""".formatted(iconsUrl, iconsUrl, iconsUrl));
        serveBytes("/api/v2/icons/steam/256.png", 200, "image/png", PNG_MAGIC);
        // The fat 1024² has NO route at all: ascending order (user decision
        // 2026-11-24, the throttled-transit post-mortem) must never even
        // request it — an unrouted hit lands in the 404 router below.
        serveBytes("/api/v2/icons/steam/a.ico", 200, "image/x-icon", new byte[]{9});

        SgdbClient.SgdbIcon icon = client.resolveIcon("k1", "578080", null, null).icon();
        assertEquals(iconsUrl + "256.png", icon.url());
        assertEquals("image/png", icon.mime());
        assertArrayEquals(PNG_MAGIC, icon.bytes());
        assertEquals(0, unknownHits.get()); // the 1024² was never asked for
    }

    @Test
    void anIcoOnlyListServesTheFirstIcoWithTheIcoMime() {
        serveJson(apiPath(), 200, """
                {"success":true,"data":[
                  {"mime":"image/vnd.microsoft.icon","width":0,"height":0,
                   "url":"%sone.ico"},
                  {"mime":"image/vnd.microsoft.icon","width":0,"height":0,
                   "url":"%stwo.ico"}
                ]}""".formatted(iconsUrl, iconsUrl));
        // The CDN's REAL content type (live probe 2026-11-23): the .ico files
        // answer image/x-icon, not image/png — this exact shape broke the old
        // converter-backed download while the mocked IMAGE_PNG sailed through.
        serveBytes("/api/v2/icons/steam/one.ico", 200, "image/x-icon", new byte[]{
                0, 0, 1, 0, 5, 5, 5, 5, 5, 5, 5, 5});

        SgdbClient.SgdbIcon icon = client.resolveIcon("k1", "578080", null, null).icon();
        assertEquals(iconsUrl + "one.ico", icon.url());
        // The stored mime is the RESPONSE header's, verbatim — not the API
        // metadata's "image/vnd.microsoft.icon" and not the URL's extension.
        assertEquals("image/x-icon", icon.mime());
    }

    @Test
    void anOverCapSmallestFallsBackUpToTheNextPngThatFits() {
        serveJson(apiPath(), 200, """
                {"success":true,"data":[
                  {"mime":"image/png","width":256,"height":256,
                   "url":"%s256.png"},
                  {"mime":"image/png","width":512,"height":512,
                   "url":"%s512.png"}
                ]}""".formatted(iconsUrl, iconsUrl));
        // The ascending walk's safety net: when the SMALLEST candidate turns
        // out over the cache cap, the next one up serves (refusing the whole
        // game was the 2026-11-23 bug).
        serveBytes("/api/v2/icons/steam/256.png", 200, "image/png",
                new byte[oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES + 1]);
        serveBytes("/api/v2/icons/steam/512.png", 200, "image/png", PNG_MAGIC);

        SgdbClient.SgdbIcon icon = client.resolveIcon("k1", "578080", null, null).icon();
        assertEquals(iconsUrl + "512.png", icon.url());
        assertArrayEquals(PNG_MAGIC, icon.bytes());
    }

    @Test
    void a404IsAnAnswerNotAnOutage() {
        serveBytes(apiPath(), 404, "application/json", new byte[0]);
        assertNull(client.resolveIcon("k1", "578080", null, null).icon());
    }

    @Test
    void theBreakerParksOutboundAfterConsecutiveFailures() {
        AtomicInteger hits = new AtomicInteger();
        routes.put(apiPath(), exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        for (int failure = 0; failure < 3; failure++) {
            assertThrows(IgdbException.class, () -> client.resolveIcon("k1", "578080", null, null));
        }

        // The 4th call fails INSTANTLY without another request.
        IgdbException parked = assertThrows(IgdbException.class,
                () -> client.resolveIcon("k1", "578080", null, null));
        assertTrue(parked.getMessage().contains("outbound paused"));
        assertEquals(3, hits.get());
    }

    @Test
    void strangledConnectionsChainViaRangeUntilTheFileCompletes() throws Exception {
        byte[] whole = new byte[60_000];
        for (int index = 0; index < whole.length; index++) {
            whole[index] = (byte) index;
        }
        serveJson(apiPath(), 200, """
                {"success":true,"data":[
                  {"mime":"image/png","width":256,"height":256,
                   "url":"%s256.png"}
                ]}""".formatted(iconsUrl));
        CountDownLatch releaseStrangler = new CountDownLatch(1);
        AtomicInteger connections = new AtomicInteger();
        routes.put("/api/v2/icons/steam/256.png", exchange -> {
            connections.incrementAndGet();
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            int offset = 0;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                offset = Integer.parseInt(rangeHeader.substring("bytes=".length()).replace("-", ""));
                exchange.sendResponseHeaders(206, whole.length - offset);
            } else {
                exchange.sendResponseHeaders(200, whole.length);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                // the measured transit shape: the first ~20 KB flow, then silence
                out.write(whole, offset, Math.min(20_000, whole.length - offset));
                out.flush();
                releaseStrangler.await(); // strangle: no more bytes on THIS connection
            } catch (InterruptedException released) {
                // teardown — the socket is dead anyway
            } catch (IOException ignored) {
                // the client already hung up after its stall deadline
            }
        });
        SgdbClient shortStall = newClient(Duration.ofSeconds(5), 200);

        // ONE attempt chains its strangled connections via Range until the
        // declared length is reached — the 60 KB file lands assembled.
        SgdbClient.Resolution outcome = shortStall.resolveIcon("k1", "578080", null, null);
        assertEquals(whole.length, outcome.icon().bytes().length);
        assertArrayEquals(whole, outcome.icon().bytes());
        assertTrue(connections.get() >= 3, "expected >=3 chained connections, got " + connections.get());
        releaseStrangler.countDown();
    }

    @Test
    void aPartialSurvivesTheConnectionCapAndTheNextAttemptResumesIt() throws Exception {
        byte[] whole = new byte[60_000];
        for (int index = 0; index < whole.length; index++) {
            whole[index] = (byte) (index * 7);
        }
        serveJson(apiPath(), 200, """
                {"success":true,"data":[
                  {"mime":"image/png","width":256,"height":256,
                   "url":"%s256.png"}
                ]}""".formatted(iconsUrl));
        CountDownLatch releaseStrangler = new CountDownLatch(1);
        AtomicInteger connections = new AtomicInteger();
        routes.put("/api/v2/icons/steam/256.png", exchange -> {
            connections.incrementAndGet();
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            int offset = 0;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                offset = Integer.parseInt(rangeHeader.substring("bytes=".length()).replace("-", ""));
                exchange.sendResponseHeaders(206, whole.length - offset);
            } else {
                exchange.sendResponseHeaders(200, whole.length);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                // a 3 KB burst per connection: the 12-connection cap reaches only
                // 36 KB of the 60 KB file — the attempt must return the PARTIAL
                out.write(whole, offset, Math.min(3_000, whole.length - offset));
                out.flush();
                releaseStrangler.await();
            } catch (InterruptedException released) {
                // teardown
            } catch (IOException ignored) {
                // the client hung up after its stall deadline
            }
        });
        SgdbClient shortStall = newClient(Duration.ofSeconds(5), 150);

        // attempt 1: the cap exhausts first — the 36 KB partial comes back
        SgdbClient.Resolution first = shortStall.resolveIcon("k1", "578080", null, null);
        assertNull(first.icon());
        assertEquals(iconsUrl + "256.png", first.partialUrl());
        assertEquals(3_000 * SgdbClient.MAX_CONNECTIONS_PER_ATTEMPT, first.partialBytes().length);
        assertArrayEquals(java.util.Arrays.copyOf(whole, first.partialBytes().length), first.partialBytes());

        // attempt 2: released strangle + the resume — the rest flows, DONE
        releaseStrangler.countDown();
        SgdbClient.Resolution second = shortStall.resolveIcon("k1", "578080",
                first.partialUrl(), first.partialBytes());
        assertEquals(whole.length, second.icon().bytes().length);
        assertArrayEquals(whole, second.icon().bytes());
    }

    @Test
    void aServerThatNeverAnswersDiesAtTheBudgetNotTheQueuesLifetime() throws Exception {
        // The 2026-11-24 wedge shape: the connection is OPEN but nothing ever
        // arrives — no silence-timeout can catch a trickling upstream, only
        // the hard total budget can. A short budget keeps the test fast.
        CountDownLatch releaseNeverAnsweringHandler = new CountDownLatch(1);
        SgdbClient quickBudgetClient = newClient(Duration.ofMillis(300), 300);
        routes.put(apiPath(), exchange -> {
            try {
                releaseNeverAnsweringHandler.await(); // never sends a header byte
            } catch (InterruptedException ignored) {
                // the teardown released us — the socket is dead anyway
            } finally {
                quietlyClose(exchange);
            }
        });

        long startedAtMillis = System.currentTimeMillis();
        IgdbException blown = assertThrows(IgdbException.class,
                () -> quickBudgetClient.resolveIcon("k1", "578080", null, null));
        long tookMillis = System.currentTimeMillis() - startedAtMillis;

        assertTrue(blown.getMessage().contains("budget") || blown.getMessage().contains("failed"),
                blown.getMessage());
        // roughly the budget, not seconds of timeouts and not forever
        assertTrue(tookMillis < 5_000, "took " + tookMillis + " ms");
        releaseNeverAnsweringHandler.countDown();
    }

    private static void quietlyClose(HttpExchange exchange) {
        try {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        } catch (IOException ignored) {
            // the client already hung up — exactly the expected end state
        }
    }
}
