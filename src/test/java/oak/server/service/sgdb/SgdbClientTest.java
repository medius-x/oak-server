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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SGDB outbound door against a REAL localhost HTTP server (the JDK's
 * built-in {@code com.sun.net.httpserver}) — the 2026-11-24 transport
 * migration to {@link HttpClient} retired RestClient/MockRestServiceServer,
 * and a socket-level server is the honest seam for a client whose whole point
 * is transport behaviour: the DOCUMENTED platform route with the Bearer key,
 * the pick policy over the official list (largest PNG wins, an ICO serves when
 * PNGs are absent — the Wardogs shape), the cache-cap walk, and the HARD
 * request budget — a server that never answers must cost the budget, not the
 * queue's lifetime. The shared breaker parks the door after consecutive
 * failures with an instant, networkless failure.
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
        client = new SgdbClient(iconsUrl, Duration.ofSeconds(5),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new UpstreamCircuitBreaker(Clock.systemUTC()));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverLane.shutdownNow();
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

        assertNull(client.bestOfficialIcon("k1", "578080"));
        assertEquals(1, hits.get());
        assertEquals(0, unknownHits.get()); // the URL shape is exactly the platform route
    }

    @Test
    void theLargestOfficialPngWinsAndItsBytesRideAlong() {
        serveJson(apiPath(), 200, """
                {"success":true,"data":[
                  {"mime":"image/vnd.microsoft.icon","width":0,"height":0,
                   "url":"%sa.ico"},
                  {"mime":"image/png","width":256,"height":256,
                   "url":"%s256.png"},
                  {"mime":"image/png","width":1024,"height":1024,
                   "url":"%s1024.png"}
                ]}""".formatted(iconsUrl, iconsUrl, iconsUrl));
        serveBytes("/api/v2/icons/steam/1024.png", 200, "image/png", new byte[]{1, 2, 3});
        serveBytes("/api/v2/icons/steam/256.png", 200, "image/png", new byte[]{7});
        serveBytes("/api/v2/icons/steam/a.ico", 200, "image/x-icon", new byte[]{9});

        SgdbClient.SgdbIcon icon = client.bestOfficialIcon("k1", "578080");
        assertEquals(iconsUrl + "1024.png", icon.url());
        assertEquals("image/png", icon.mime());
        assertArrayEquals(new byte[]{1, 2, 3}, icon.bytes());
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
        serveBytes("/api/v2/icons/steam/one.ico", 200, "image/x-icon", new byte[]{9});

        SgdbClient.SgdbIcon icon = client.bestOfficialIcon("k1", "578080");
        assertEquals(iconsUrl + "one.ico", icon.url());
        // The stored mime is the RESPONSE header's, verbatim — not the API
        // metadata's "image/vnd.microsoft.icon" and not the URL's extension.
        assertEquals("image/x-icon", icon.mime());
    }

    @Test
    void anOverCapWinnerFallsBackToTheNextPngThatFits() {
        serveJson(apiPath(), 200, """
                {"success":true,"data":[
                  {"mime":"image/png","width":1024,"height":1024,
                   "url":"%s1024.png"},
                  {"mime":"image/png","width":512,"height":512,
                   "url":"%s512.png"}
                ]}""".formatted(iconsUrl, iconsUrl));
        // Heavy Rain's shape (2026-11-23): the 1024² is 725 KB — over the cap;
        // its 512² fits. The pick must walk on instead of refusing the game.
        serveBytes("/api/v2/icons/steam/1024.png", 200, "image/png",
                new byte[oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES + 1]);
        serveBytes("/api/v2/icons/steam/512.png", 200, "image/png", new byte[]{4});

        SgdbClient.SgdbIcon icon = client.bestOfficialIcon("k1", "578080");
        assertEquals(iconsUrl + "512.png", icon.url());
        assertArrayEquals(new byte[]{4}, icon.bytes());
    }

    @Test
    void a404IsAnAnswerNotAnOutage() {
        serveBytes(apiPath(), 404, "application/json", new byte[0]);
        assertNull(client.bestOfficialIcon("k1", "578080"));
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
            assertThrows(IgdbException.class, () -> client.bestOfficialIcon("k1", "578080"));
        }

        // The 4th call fails INSTANTLY without another request.
        IgdbException parked = assertThrows(IgdbException.class, () -> client.bestOfficialIcon("k1", "578080"));
        assertTrue(parked.getMessage().contains("outbound paused"));
        assertEquals(3, hits.get());
    }

    @Test
    void aServerThatNeverAnswersDiesAtTheBudgetNotTheQueuesLifetime() throws Exception {
        // The 2026-11-24 wedge shape: the connection is OPEN but nothing ever
        // arrives — no silence-timeout can catch a trickling upstream, only
        // the hard total budget can. A short budget keeps the test fast.
        CountDownLatch releaseNeverAnsweringHandler = new CountDownLatch(1);
        SgdbClient quickBudgetClient = new SgdbClient(iconsUrl, Duration.ofMillis(300),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new UpstreamCircuitBreaker(Clock.systemUTC()));
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
                () -> quickBudgetClient.bestOfficialIcon("k1", "578080"));
        long tookMillis = System.currentTimeMillis() - startedAtMillis;

        assertTrue(blown.getMessage().contains("budget"), blown.getMessage());
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
