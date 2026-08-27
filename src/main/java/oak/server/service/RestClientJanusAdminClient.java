package oak.server.service;

import lombok.extern.slf4j.Slf4j;
import oak.server.config.JanusProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link JanusAdminClient} over Spring's {@link RestClient} — the first outbound HTTP integration of
 * oak-server besides the MinIO SDK. One client, two base URLs: Admin API requests post to
 * {@code admin-url} with {@code admin_secret} in the body; regular API requests (sessions, handles,
 * videoroom messages) go to {@code rest-url} with the access token as a <b>body field</b>
 * ({@code "token": ...}) — live-probed quirk of this canyan/Janus 1.1.4 build: it IGNORES query
 * tokens on POSTs (403) while honouring them on GETs, so the long-poll {@link #poll} is the one
 * request carrying {@code ?token=}. Timeouts are tight (3s connect / 10s read — the read budget has
 * to cover one long-poll GET, which Janus holds open until an event arrives or the transport gives
 * up); every failure surfaces as {@link JanusAdminException} except {@link #poll}, where a timed-out
 * empty poll is a legal "no events" answer.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "oak.janus", name = "admin-enabled", havingValue = "true")
public class RestClientJanusAdminClient implements JanusAdminClient {

    private static final String VIDEOROOM_PLUGIN = "janus.plugin.videoroom";

    private final JanusProperties properties;
    private final RestClient restClient;

    public RestClientJanusAdminClient(JanusProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    // --- Admin/Monitor API (global requests, admin_secret in the body) ---

    @Override
    public JsonNode addToken(String token) {
        Map<String, Object> body = adminRequest("add_token", properties.adminSecret());
        body.put("token", token);
        body.put("plugins", List.of(VIDEOROOM_PLUGIN));
        return post(properties.adminUrl(), body);
    }

    @Override
    public JsonNode removeToken(String token) {
        Map<String, Object> body = adminRequest("remove_token", properties.adminSecret());
        body.put("token", token);
        return post(properties.adminUrl(), body);
    }

    @Override
    public JsonNode listTokens() {
        return post(properties.adminUrl(), adminRequest("list_tokens", properties.adminSecret()));
    }

    // --- Regular REST API (?token= on every request) ---

    @Override
    public long createSession(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("janus", "create");
        body.put("transaction", transaction());
        addTokenField(body, token);
        return requireId(post(properties.restUrl(), body), "create session");
    }

    @Override
    public long attach(long sessionId, String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("janus", "attach");
        body.put("plugin", VIDEOROOM_PLUGIN);
        body.put("transaction", transaction());
        addTokenField(body, token);
        return requireId(post(properties.restUrl() + "/" + sessionId, body), "attach videoroom");
    }

    @Override
    public JsonNode message(long sessionId, long handleId, Map<String, Object> body, String token) {
        Map<String, Object> request = new HashMap<>();
        request.put("janus", "message");
        request.put("transaction", transaction());
        request.put("body", body);
        addTokenField(request, token);
        return post(properties.restUrl() + "/" + sessionId + "/" + handleId, request);
    }

    @Override
    public List<JsonNode> poll(long sessionId, String token) {
        JsonNode events;
        try {
            events = restClient.get()
                    .uri(properties.restUrl() + "/" + sessionId + "?token=" + token + "&maxev=10")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException timeout) {
            // A long-poll that outlives the read timeout with no events is a legal "nothing pending".
            return List.of();
        }
        if (events == null || !events.isArray()) {
            return List.of();
        }
        List<JsonNode> pending = new ArrayList<>();
        events.forEach(pending::add);
        return pending;
    }

    @Override
    public JsonNode destroySession(long sessionId, String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("janus", "destroy");
        body.put("transaction", transaction());
        addTokenField(body, token);
        return post(properties.restUrl() + "/" + sessionId, body);
    }

    // --- helpers ---

    /** POSTs authenticate with the token in the BODY (query tokens are ignored on POSTs — live-probed). */
    private static void addTokenField(Map<String, Object> body, String token) {
        if (token != null && !token.isBlank()) {
            body.put("token", token);
        }
    }

    private static Map<String, Object> adminRequest(String janus, String adminSecret) {
        Map<String, Object> body = new HashMap<>();
        body.put("janus", janus);
        body.put("transaction", transaction());
        body.put("admin_secret", adminSecret);
        return body;
    }

    private static String transaction() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    private JsonNode post(String url, Map<String, Object> body) {
        try {
            JsonNode response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new JanusAdminException("empty response from " + url);
            }
            return response;
        } catch (RestClientException failure) {
            throw new JanusAdminException("Janus request failed (" + url + "): " + failure.getMessage());
        }
    }

    private static long requireId(JsonNode response, String step) {
        if ("error".equals(response.path("janus").asText())) {
            throw new JanusAdminException(step + " failed: " + response.path("error").path("reason").asText());
        }
        long id = response.path("data").path("id").asLong(0);
        if (id == 0) {
            throw new JanusAdminException(step + " returned no id: " + response);
        }
        return id;
    }
}
