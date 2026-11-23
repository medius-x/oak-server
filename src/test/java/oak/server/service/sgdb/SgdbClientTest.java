package oak.server.service.sgdb;

import oak.server.service.igdb.IgdbException;
import oak.server.service.igdb.UpstreamCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The SGDB outbound door against a MockRestServiceServer: the DOCUMENTED
 * platform route with the Bearer key, the pick policy over the official list
 * (largest PNG wins, an ICO serves when PNGs are absent — the Wardogs shape),
 * and the shared breaker behaviour (three consecutive failures park the door
 * with an instant, networkless failure).
 */
class SgdbClientTest {

    private static final String ICONS_URL =
            "https://www.steamgriddb.com/api/v2/icons/steam/578080?styles=official";

    private MockRestServiceServer server;
    private SgdbClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SgdbClient(builder.build(), new UpstreamCircuitBreaker(java.time.Clock.systemUTC()));
    }

    @Test
    void queriesTheDocumentedPlatformRouteWithTheBearerKey() {
        server.expect(requestTo(ICONS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer k1"))
                .andRespond(withSuccess("{\"success\":true,\"data\":[]}", MediaType.APPLICATION_JSON));

        assertNull(client.bestOfficialIcon("k1", "578080"));
        server.verify();
    }

    @Test
    void theLargestOfficialPngWinsAndItsBytesRideAlong() {
        server.expect(requestTo(ICONS_URL))
                .andRespond(withSuccess("""
                        {"success":true,"data":[
                          {"mime":"image/vnd.microsoft.icon","width":0,"height":0,
                           "url":"https://cdn/a.ico"},
                          {"mime":"image/png","width":256,"height":256,
                           "url":"https://cdn/256.png"},
                          {"mime":"image/png","width":1024,"height":1024,
                           "url":"https://cdn/1024.png"}
                        ]}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://cdn/1024.png"))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));

        SgdbClient.SgdbIcon icon = client.bestOfficialIcon("k1", "578080");
        assertEquals("https://cdn/1024.png", icon.url());
        assertEquals("image/png", icon.mime());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1, 2, 3}, icon.bytes());
        server.verify();
    }

    @Test
    void anIcoOnlyListServesTheFirstIcoWithTheIcoMime() {
        server.expect(requestTo(ICONS_URL))
                .andRespond(withSuccess("""
                        {"success":true,"data":[
                          {"mime":"image/vnd.microsoft.icon","width":0,"height":0,
                           "url":"https://cdn/one.ico"},
                          {"mime":"image/vnd.microsoft.icon","width":0,"height":0,
                           "url":"https://cdn/two.ico"}
                        ]}""", MediaType.APPLICATION_JSON));
        // The CDN's REAL content type (live probe 2026-11-23): the .ico files
        // answer image/x-icon, not image/png — this exact shape broke the
        // test server's download with "Error while extracting response for
        // type [byte[]] and content type [image/x-icon]" while the old mocked
        // IMAGE_PNG answer sailed through.
        server.expect(requestTo("https://cdn/one.ico"))
                .andRespond(withSuccess(new byte[]{9}, MediaType.parseMediaType("image/x-icon")));

        SgdbClient.SgdbIcon icon = client.bestOfficialIcon("k1", "578080");
        assertEquals("https://cdn/one.ico", icon.url());
        // The stored mime is the RESPONSE header's, verbatim — not the API
        // metadata's "image/vnd.microsoft.icon" and not the URL's extension.
        assertEquals("image/x-icon", icon.mime());
        server.verify();
    }

    @Test
    void anOverCapWinnerFallsBackToTheNextPngThatFits() {
        server.expect(requestTo(ICONS_URL))
                .andRespond(withSuccess("""
                        {"success":true,"data":[
                          {"mime":"image/png","width":1024,"height":1024,
                           "url":"https://cdn/1024.png"},
                          {"mime":"image/png","width":512,"height":512,
                           "url":"https://cdn/512.png"}
                        ]}""", MediaType.APPLICATION_JSON));
        // Heavy Rain's shape (2026-11-23): the 1024² is 725 KB — over the cap;
        // its 512² fits. The pick must walk on instead of refusing the game.
        server.expect(requestTo("https://cdn/1024.png"))
                .andRespond(withSuccess(new byte[oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES + 1],
                        MediaType.IMAGE_PNG));
        server.expect(requestTo("https://cdn/512.png"))
                .andRespond(withSuccess(new byte[]{4}, MediaType.IMAGE_PNG));

        SgdbClient.SgdbIcon icon = client.bestOfficialIcon("k1", "578080");
        assertEquals("https://cdn/512.png", icon.url());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{4}, icon.bytes());
        server.verify();
    }

    @Test
    void a404IsAnAnswerNotAnOutage() {
        server.expect(requestTo(ICONS_URL))
                .andRespond(withStatus(NOT_FOUND));
        assertNull(client.bestOfficialIcon("k1", "578080"));
        server.verify();
    }

    @Test
    void theBreakerParksOutboundAfterConsecutiveFailures() {
        for (int failure = 0; failure < 3; failure++) {
            server.expect(requestTo(ICONS_URL)).andRespond(withServerError());
        }
        for (int failure = 0; failure < 3; failure++) {
            assertThrows(IgdbException.class, () -> client.bestOfficialIcon("k1", "578080"));
        }

        // The 4th call fails INSTANTLY without another request — no expectation left.
        IgdbException parked = assertThrows(IgdbException.class, () -> client.bestOfficialIcon("k1", "578080"));
        assertTrue(parked.getMessage().contains("outbound paused"));
        server.verify();
    }
}
