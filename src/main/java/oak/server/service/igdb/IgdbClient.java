package oak.server.service.igdb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * The single outbound door to {@code api.igdb.com/v4}: POSTs Apicalypse query bodies
 * with the {@code Client-ID} + {@code Authorization: Bearer} headers (credentials are
 * never a URL part — URLs land in logs). Self-heals the two transient failures: a 401
 * remints the token ONCE through {@link IgdbAuthService#invalidate} (the ~60-day
 * expiry or a settings change), a 429 backs off once for {@link #retryPauseMillis}
 * (the shared 4-requests-per-second budget — the match cache keeps collisions rare).
 * Everything else surfaces as {@link IgdbException} → HTTP 502.
 */
@Slf4j
@Service
public class IgdbClient {

    private static final String BASE_URL = "https://api.igdb.com/v4/";

    private final IgdbAuthService authService;
    private final RestClient restClient;

    /** 429 backoff; package-private so the unit test shrinks it. */
    long retryPauseMillis = 1_200;

    @Autowired
    public IgdbClient(IgdbAuthService authService) {
        this(authService, defaultClient());
    }

    /** Test seam: a RestClient bound to a MockRestServiceServer. */
    IgdbClient(IgdbAuthService authService, RestClient restClient) {
        this.authService = authService;
        this.restClient = restClient;
    }

    private static RestClient defaultClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Runs one Apicalypse query against an IGDB endpoint and answers the parsed JSON
     * array. The body must already be sanitized where user input is embedded — the
     * callers build it from {@link IgdbNames} output only.
     */
    public JsonNode query(String endpoint, String apicalypseBody) {
        try {
            return exchange(endpoint, apicalypseBody, true);
        } catch (HttpClientErrorException.Unauthorized staleToken) {
            authService.invalidate(); // drop the cached token — the mint below makes a fresh one
            IgdbAuthService.IgdbCredentials fresh = authService.credentials();
            log.info("IGDB 401 — reminted the token and retrying once");
            return exchangeWith(endpoint, apicalypseBody, fresh);
        } catch (HttpClientErrorException failure) {
            throw new IgdbException("IGDB rejected the query (" + endpoint + "): "
                    + failure.getStatusCode() + " " + failure.getResponseBodyAsString());
        }
    }

    private JsonNode exchange(String endpoint, String apicalypseBody, boolean rateRetryAllowed) {
        IgdbAuthService.IgdbCredentials credentials = authService.credentials();
        try {
            return post(endpoint, apicalypseBody, credentials);
        } catch (HttpClientErrorException.TooManyRequests limited) {
            if (!rateRetryAllowed) {
                throw new IgdbException("IGDB rate limit exhausted (429 twice)");
            }
            sleepQuietly();
            log.info("IGDB 429 — backing off {} ms and retrying once", retryPauseMillis);
            return post(endpoint, apicalypseBody, credentials);
        }
    }

    private JsonNode exchangeWith(String endpoint, String apicalypseBody,
                                  IgdbAuthService.IgdbCredentials credentials) {
        try {
            return post(endpoint, apicalypseBody, credentials);
        } catch (HttpClientErrorException.TooManyRequests limited) {
            sleepQuietly();
            return post(endpoint, apicalypseBody, credentials);
        }
    }

    private JsonNode post(String endpoint, String apicalypseBody,
                          IgdbAuthService.IgdbCredentials credentials) {
        try {
            return restClient.post()
                    .uri(BASE_URL + endpoint)
                    .header("Client-ID", credentials.clientId())
                    .header("Authorization", "Bearer " + credentials.accessToken())
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(apicalypseBody)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException failure) {
            throw failure; // 401/429 handled by the callers; the rest becomes IgdbException below
        } catch (RestClientException failure) {
            throw new IgdbException("IGDB request failed (" + endpoint + "): " + failure.getMessage(),
                    failure);
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(retryPauseMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
