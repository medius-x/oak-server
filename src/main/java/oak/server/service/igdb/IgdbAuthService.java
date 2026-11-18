package oak.server.service.igdb;

import lombok.extern.slf4j.Slf4j;
import oak.server.entity.ServerProfile;
import oak.server.repository.ServerProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;

/**
 * The Twitch application-access-token lifecycle of the IGDB integration. One token is
 * minted via the OAuth {@code client_credentials} grant and kept in memory until it
 * nears expiry (~60 days) or {@link #invalidate()} drops it (a 401 from IGDB, or the
 * admin editing the credentials) — the NEXT {@link #credentials()} remints lazily.
 * Twitch allows at most 25 ACTIVE tokens per application, deactivating the OLDEST
 * past the cap: a fresh mint every ~2 months (plus rare 401 remints) never approaches
 * it, and the token in hand is always the newest, so a cap cleanup can never kill it.
 *
 * <p>Credentials are read from the {@link ServerProfile} row LIVE — the admin can
 * paste a new pair in Server Settings without a restart; the settings mutation calls
 * {@link #invalidate()} so the very next query mints against the new pair.
 */
@Slf4j
@Service
public class IgdbAuthService {

    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";

    /** Remint this long before the real expiry — a clock skew / zone hiccup margin. */
    private static final Duration EXPIRY_SAFETY_MARGIN = Duration.ofHours(1);

    /** Client ID + access token riding the same request headers — minted and dropped together. */
    public record IgdbCredentials(String clientId, String accessToken) {
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }

    private final ServerProfileRepository profiles;
    private final RestClient restClient;

    private volatile CachedToken current;

    @Autowired
    public IgdbAuthService(ServerProfileRepository profiles) {
        this(profiles, defaultClient());
    }

    /** Test seam: a RestClient bound to a MockRestServiceServer. */
    IgdbAuthService(ServerProfileRepository profiles, RestClient restClient) {
        this.profiles = profiles;
        this.restClient = restClient;
    }

    private static RestClient defaultClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Both Twitch credentials present — the integration's on/off switch. */
    public static boolean isConfigured(ServerProfile profile) {
        return profile != null
                && profile.getIgdbClientId() != null && !profile.getIgdbClientId().isBlank()
                && profile.getIgdbClientSecret() != null && !profile.getIgdbClientSecret().isBlank();
    }

    public boolean configured() {
        return isConfigured(profiles.singleton());
    }

    /** A usable (clientId, token) pair, minting fresh when the cache is empty/near-expiry. */
    public synchronized IgdbCredentials credentials() {
        if (current == null || !current.expiresAt().isAfter(Instant.now().plus(EXPIRY_SAFETY_MARGIN))) {
            current = mint();
            log.info("minted a fresh Twitch application token (expires in ~{} days)",
                    Duration.between(Instant.now(), current.expiresAt()).toDays());
        }
        return new IgdbCredentials(profiles.singleton().getIgdbClientId(), current.accessToken());
    }

    /** Drops the cached token — the next {@link #credentials()} remints. Called on any
     *  401 from IGDB (stale/rotated token) and on a credentials change in settings. */
    public synchronized void invalidate() {
        current = null;
    }

    private CachedToken mint() {
        ServerProfile profile = profiles.singleton();
        if (!isConfigured(profile)) {
            throw new IgdbNotConfiguredException();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", profile.getIgdbClientId());
        form.add("client_secret", profile.getIgdbClientSecret()); // form BODY — never a logged URL
        form.add("grant_type", "client_credentials");
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException failure) {
            // 400 carries Twitch's own reason ("invalid client secret" etc.) — no secret in it
            throw new IgdbException("Twitch token endpoint rejected the credentials: "
                    + failure.getStatusCode() + " " + failure.getResponseBodyAsString());
        } catch (RestClientException failure) {
            throw new IgdbException("Twitch token request failed: " + failure.getMessage(), failure);
        }
        String accessToken = response == null ? null : response.path("access_token").asText(null);
        long expiresInSeconds = response == null ? 0 : response.path("expires_in").asLong(0);
        if (accessToken == null || accessToken.isBlank() || expiresInSeconds <= 0) {
            throw new IgdbException("Twitch token response missing access_token/expires_in");
        }
        return new CachedToken(accessToken, Instant.now().plusSeconds(expiresInSeconds));
    }
}
