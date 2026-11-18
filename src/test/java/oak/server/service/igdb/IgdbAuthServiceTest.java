package oak.server.service.igdb;

import oak.server.entity.ServerProfile;
import oak.server.repository.ServerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Twitch token lifecycle against a MockRestServiceServer-bound RestClient: the
 * form-body mint (the secret rides the BODY, never the URL), the in-memory cache
 * (one mint serves many consumers), the invalidate → remint path, and the clean
 * failure translations (missing credentials, Twitch 400, malformed response).
 */
class IgdbAuthServiceTest {

    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";

    private final ServerProfileRepository profiles = mock(ServerProfileRepository.class);

    private MockRestServiceServer server;
    private IgdbAuthService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new IgdbAuthService(profiles, builder.build());
    }

    private ServerProfile configuredProfile() {
        ServerProfile profile = new ServerProfile("OAK");
        profile.setIgdbClientId("client-id-123");
        profile.setIgdbClientSecret("client-secret-456");
        when(profiles.singleton()).thenReturn(profile);
        return profile;
    }

    @Test
    void mintSendsTheCredentialsInTheFormBodyAndCachesTheToken() {
        configuredProfile();
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(new org.springframework.util.LinkedMultiValueMap<>(
                        java.util.Map.of("client_id", java.util.List.of("client-id-123"),
                                "client_secret", java.util.List.of("client-secret-456"),
                                "grant_type", java.util.List.of("client_credentials")))))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok\",\"expires_in\":5587808}", MediaType.APPLICATION_JSON));

        IgdbAuthService.IgdbCredentials first = service.credentials();
        assertEquals("tok", first.accessToken());
        assertEquals("client-id-123", first.clientId());

        // the cached token serves the second call — no second HTTP round-trip
        assertEquals("tok", service.credentials().accessToken());
        server.verify();
    }

    @Test
    void invalidateForcesTheNextCallToRemint() {
        configuredProfile();
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"one\",\"expires_in\":5587808}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"two\",\"expires_in\":5587808}",
                        MediaType.APPLICATION_JSON));

        assertEquals("one", service.credentials().accessToken());
        service.invalidate();
        assertEquals("two", service.credentials().accessToken());
        server.verify();
    }

    @Test
    void missingCredentialsAnswerNotConfiguredWithoutAnyHttp() {
        when(profiles.singleton()).thenReturn(new ServerProfile("OAK")); // no IGDB pair
        assertThrows(IgdbNotConfiguredException.class, () -> service.credentials());
        server.verify(); // nothing was ever sent
    }

    @Test
    void twitchRejectionBecomesIgdbExceptionCarryingTheReason() {
        configuredProfile();
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"invalid client secret\"}"));
        IgdbException failure = assertThrows(IgdbException.class, () -> service.credentials());
        assertEquals(true, failure.getMessage().contains("invalid client secret"));
    }

    @Test
    void malformedTokenResponseBecomesIgdbException() {
        configuredProfile();
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(IgdbException.class, () -> service.credentials());
    }
}
