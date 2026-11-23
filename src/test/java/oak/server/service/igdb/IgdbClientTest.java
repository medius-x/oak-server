package oak.server.service.igdb;

import oak.server.entity.ServerProfile;
import oak.server.repository.ServerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.*;

/**
 * The outbound IGDB door against a MockRestServiceServer: one shared RestClient for
 * the token endpoint and the API (both services accept an injected client — the test
 * seam), asserting the Client-ID/Bearer headers, the TEXT_PLAIN Apicalypse body, and
 * the two self-healing paths: 401 → one remint + retry, 429 → one backoff + retry.
 * The retry pause is shrunk to nothing — the BACKOFF behaviour is under test, not the
 * nap itself.
 */
class IgdbClientTest {

    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String GAMES_URL = "https://api.igdb.com/v4/games";

    private MockRestServiceServer server;
    private IgdbClient client;

    @BeforeEach
    void setUp() {
        ServerProfileRepository profiles = mock(ServerProfileRepository.class);
        ServerProfile profile = new ServerProfile("OAK");
        profile.setIgdbClientId("cid");
        profile.setIgdbClientSecret("csecret");
        when(profiles.singleton()).thenReturn(profile);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient shared = builder.build();
        client = new IgdbClient(new IgdbAuthService(profiles, shared), shared);
        client.retryPauseMillis = 0; // the backoff is asserted, not the nap
    }

    private void expectToken(String accessToken) {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"" + accessToken + "\",\"expires_in\":5587808}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void querySendsTheCredentialHeadersAndTheApicalypseBody() {
        expectToken("tok1");
        server.expect(requestTo(GAMES_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Client-ID", "cid"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok1"))
                .andExpect(content().string("fields name; search \"witcher 3\";"))
                .andRespond(withSuccess("[{\"id\":1942,\"name\":\"The Witcher 3: Wild Hunt\"}]",
                        MediaType.APPLICATION_JSON));

        JsonNode answer = client.query("games", "fields name; search \"witcher 3\";");
        assertEquals(1942, answer.get(0).path("id").asLong());
        server.verify();
    }

    @Test
    void a401RemintsTheTokenOnceAndRetries() {
        expectToken("stale");
        server.expect(requestTo(GAMES_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale"))
                .andRespond(withStatus(UNAUTHORIZED));
        expectToken("fresh");
        server.expect(requestTo(GAMES_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        JsonNode answer = client.query("games", "fields name;");
        assertTrue(answer.isArray());
        assertTrue(answer.isEmpty());
        server.verify();
    }

    @Test
    void a429BacksOffOnceAndRetries() {
        expectToken("tok1");
        server.expect(requestTo(GAMES_URL))
                .andRespond(withStatus(TOO_MANY_REQUESTS));
        server.expect(requestTo(GAMES_URL))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        JsonNode answer = client.query("games", "fields name;");
        assertTrue(answer.isEmpty());
        server.verify();
    }

    @Test
    void anUpstreamFailureBecomesIgdbException() {
        expectToken("tok1");
        server.expect(requestTo(GAMES_URL))
                .andRespond(withServerError());
        assertThrows(IgdbException.class, () -> client.query("games", "fields name;"));
    }

    @Test
    void theBreakerParksOutboundAfterConsecutiveFailures() {
        expectToken("tok1");
        for (int failure = 0; failure < 3; failure++) {
            server.expect(requestTo(GAMES_URL)).andRespond(withServerError());
        }
        for (int failure = 0; failure < 3; failure++) {
            assertThrows(IgdbException.class, () -> client.query("games", "fields name;"));
        }

        // The 4th call must fail INSTANTLY without another request — the mock
        // server has no expectation left, a network attempt would fail verify().
        IgdbException parked = assertThrows(IgdbException.class, () -> client.query("games", "fields name;"));
        assertTrue(parked.getMessage().contains("outbound paused"));
        server.verify();
    }

    @Test
    void aProbeAfterTheCooldownClosesTheBreakerOnSuccess() {
        MutableTestClock clock = new MutableTestClock();
        ServerProfileRepository profiles = mock(ServerProfileRepository.class);
        ServerProfile profile = new ServerProfile("OAK");
        profile.setIgdbClientId("cid");
        profile.setIgdbClientSecret("csecret");
        when(profiles.singleton()).thenReturn(profile);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient shared = builder.build();
        IgdbClient wired = new IgdbClient(new IgdbAuthService(profiles, shared), shared,
                new UpstreamCircuitBreaker(clock));
        wired.retryPauseMillis = 0;

        expectToken("tok1");
        for (int failure = 0; failure < 3; failure++) {
            server.expect(requestTo(GAMES_URL)).andRespond(withServerError());
        }
        // All expectations are declared UP FRONT — the mock server forbids adding
        // any after requests have run; the ordered list rides out the parked call
        // (no request) until the probe lands here.
        server.expect(requestTo(GAMES_URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        for (int failure = 0; failure < 3; failure++) {
            assertThrows(IgdbException.class, () -> wired.query("games", "fields name;"));
        }
        assertThrows(IgdbException.class, () -> wired.query("games", "fields name;")); // parked

        // Past the cooldown the next call probes the wire again; a healthy
        // answer closes the breaker (and further calls flow normally).
        clock.advanceMillis(UpstreamCircuitBreaker.BASE_COOLDOWN_MILLIS + 1);
        assertTrue(wired.query("games", "fields name;").isEmpty());
        server.verify();
    }

    /** A hand-driven clock for the breaker's cooldown windows. */
    private static final class MutableTestClock extends java.time.Clock {
        private java.time.Instant now = java.time.Instant.ofEpochMilli(0);

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }
    }
}
