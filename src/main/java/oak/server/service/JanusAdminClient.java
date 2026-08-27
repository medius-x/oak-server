package oak.server.service;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Low-level transport to the Janus gateway, one abstraction so the voice-access service above it stays
 * unit-testable against fixture JSON. Two surfaces:
 * <ul>
 *   <li><b>Admin/Monitor API</b> (token bookkeeping): POSTs to {@code oak.janus.admin-url} carrying
 *       {@code admin_secret} in the body. Token requests are global — no session needed (verified
 *       against the live gateway).</li>
 *   <li><b>Regular REST API</b> (the same one the JavaFX client speaks): sessions, plugin handles and
 *       videoroom messages, with the access token as a {@code ?token=} query parameter on EVERY request
 *       (Janus runs with {@code token_auth = true}).</li>
 * </ul>
 */
public interface JanusAdminClient {

    /** Admin API: registers a stored token allowed to attach the videoroom plugin. */
    JsonNode addToken(String token);

    /** Admin API: revokes a stored token. */
    JsonNode removeToken(String token);

    /** Admin API: all stored tokens (bootstrap cleanup wipes them — they die with Janus restarts anyway). */
    JsonNode listTokens();

    /** REST API: creates a session; returns its id. Throws {@link JanusAdminException} on failure. */
    long createSession(String token);

    /** REST API: attaches the videoroom plugin; returns the handle id. */
    long attach(long sessionId, String token);

    /**
     * REST API: sends a videoroom message; returns the raw response JSON. Videoroom has BOTH answer
     * kinds: {@code create}/{@code exists}/{@code kick}/{@code moderate} answer inline (plugindata in
     * the POST response), {@code join} answers {@code ack} with the real event arriving via {@link #poll}.
     */
    JsonNode message(long sessionId, long handleId, Map<String, Object> body, String token);

    /** REST API: long-poll GET for pending events (the async answers, e.g. {@code joined}). May be empty. */
    List<JsonNode> poll(long sessionId, String token);

    /** REST API: destroys the session (best-effort — Janus reaps idle sessions anyway). */
    JsonNode destroySession(long sessionId, String token);
}
