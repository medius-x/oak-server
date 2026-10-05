package oak.server.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.config.JanusProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link JanusVoiceAccessService} against a live gateway (see the interface javadoc for the security
 * model). Two very different call patterns live here:
 *
 * <ul>
 *   <li><b>Synchronous, on the caller's thread</b> (RoomController / HTTP): {@link #issueVoiceToken}
 *       and {@link #ensureRoom} — one short REST round-trip each; failures throw
 *       {@link JanusAdminException} which the controller maps to 503.</li>
 *   <li><b>Fire-and-forget, on a dedicated single-thread executor</b> (VoiceControlHandler / STOMP
 *       thread): {@link #forceKick}, {@link #moderateAudio}, {@link #revokeTokens} — moderator actions
 *       must never block or fail the STOMP {@code clientInboundChannel}; the single thread keeps actions
 *       on the same room ordered. Failures are WARN-logged, never rethrown.</li>
 * </ul>
 *
 * <p><b>Control sessions are ephemeral, one per action</b>: mint a throwaway token → create session →
 * attach videoroom → (action) → destroy. No keepalive infrastructure to maintain, and per-action token
 * minting self-heals after a Janus restart (stored tokens are wiped by the restart — a cached server
 * token would go stale). The forced actions join the room as a <b>passive publisher</b> first: the
 * {@code joined} event's {@code publishers} array is the only place Janus exposes active publishers
 * together with their {@code streams} (audio mids) — {@code listparticipants} returns neither, and
 * {@code moderate} needs the mid (probed live against the gateway, Janus 1.1.4).
 *
 * <p><b>Known residual (v1, documented):</b> participants are matched by {@code display} (= username) —
 * a rogue may spoof someone else's display; token revocation does not tear down an established WebRTC
 * connection (Janus reaps the session within its timeout once keepalive starts failing) — instant media
 * cutoff comes from the plugin-level {@code kick} instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oak.janus", name = "admin-enabled", havingValue = "true")
public class HttpJanusVoiceAccessService implements JanusVoiceAccessService {

    /** videoroom display of the ephemeral server-side control participant (never matches a username). */
    static final String CONTROL_DISPLAY = "oak-control";
    /** Live-token history per (user, channel); the oldest is revoked via the Admin API on eviction. */
    private static final int TOKEN_HISTORY_CAP = 8;
    private static final String PIN_DOMAIN = "oak-voice-pin:";
    private static final String SECRET_DOMAIN = "oak-voice-secret:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JanusAdminClient client;
    private final JanusProperties properties;

    /** username -> (channelId -> tokens, oldest first). Read on HTTP threads, mutated on the executor too. */
    private final Map<String, Map<Long, Deque<String>>> tokensByUserChannel = new HashMap<>();

    private final ExecutorService actionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "oak-janus-admin");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Stored tokens outlive this server (they sit in Janus memory until its own restart), so a server
     * restart must wipe the slate — otherwise tokens minted before a restart would keep working with
     * no bookkeeping to revoke them. Failure is a WARN, not a boot failure: Janus may simply be down.
     */
    @PostConstruct
    void bootstrapCleanup() {
        try {
            JsonNode listed = client.listTokens();
            if (isJanusError(listed)) {
                log.warn("Janus token bootstrap cleanup skipped: {}", errorText(listed));
                return;
            }
            JsonNode tokens = listed.path("data").path("tokens");
            int wiped = 0;
            for (JsonNode token : tokens) {
                try {
                    client.removeToken(token.asText());
                    wiped++;
                } catch (RuntimeException ignored) {
                    // best-effort: a stale token dies with the next Janus restart anyway
                }
            }
            if (wiped > 0) {
                log.info("Janus bootstrap: revoked {} stale token(s)", wiped);
            }
        } catch (RuntimeException failure) {
            log.warn("Janus token bootstrap cleanup failed (gateway down?): {}", failure.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        actionExecutor.shutdownNow();
    }

    // --- synchronous path (RoomController) ---

    @Override
    public String issueVoiceToken(String username, long channelId) {
        String token = mintToken();
        JsonNode response = client.addToken(token);
        if (isJanusError(response)) {
            throw new JanusAdminException("add_token failed: " + errorText(response));
        }
        synchronized (tokensByUserChannel) {
            Deque<String> history = tokensByUserChannel
                    .computeIfAbsent(username, user -> new HashMap<>())
                    .computeIfAbsent(channelId, channel -> new ArrayDeque<>());
            history.addLast(token);
            while (history.size() > TOKEN_HISTORY_CAP) {
                String oldest = history.removeFirst();
                try {
                    client.removeToken(oldest);
                } catch (RuntimeException failure) {
                    log.debug("Eviction remove_token failed (ignored): {}", failure.getMessage());
                }
            }
        }
        return token;
    }

    @Override
    public String roomPin(long channelId) {
        return hmacSha256(properties.adminSecret(), PIN_DOMAIN + channelId);
    }

    @Override
    public void ensureRoom(long channelId) {
        String pin = roomPin(channelId);
        String secret = roomSecret(channelId);
        withControlHandle(control -> {
            if (roomExists(control, channelId)) {
                // H7: an existing room is OURS only if our pin opens it. Room ids equal channel
                // ids and videoroom tokens are plugin-scoped, not room-scoped — any member can
                // `create` the id with THEIR pin before our lazy creation, and blindly trusting
                // the exists-probe turns every legitimate join into a permanent 433. A wrong-pin
                // probe fails LOUDLY here (the /room caller gets a 503 with the cause in the
                // log) instead of every client climbing its rejoin ladder against a squatted room.
                verifyRoomPin(control, channelId, pin);
                return;
            }
            Map<String, Object> create = new HashMap<>();
            create.put("request", "create");
            create.put("room", channelId);
            create.put("secret", secret);
            create.put("pin", pin);
            create.put("permanent", false);
            create.put("publishers", 20);
            JsonNode created = client.message(control.sessionId(), control.handleId(), create, control.token());
            if (isJanusError(created) && !roomExists(control, channelId)) {
                // Lost a create race only if the room materialised anyway; otherwise a real failure.
                throw new JanusAdminException("room create failed: " + errorText(created));
            }
        });
    }

    /** Passive-joins with OUR pin and expects success — a 433 means the room exists but is not
     *  ours (squatted); anything else that is not a plain join failure rethrows as a gateway
     *  error. Package-private error-code helper for the unit test. */
    private void verifyRoomPin(ControlSession control, long channelId, String pin) {
        Map<String, Object> join = new HashMap<>();
        join.put("request", "join");
        join.put("room", channelId);
        join.put("ptype", "publisher");
        join.put("display", CONTROL_DISPLAY);
        join.put("pin", pin);
        JsonNode ack = client.message(control.sessionId(), control.handleId(), join, control.token());
        Integer ackError = pluginErrorCode(ack);
        if (ackError != null) {
            throw pinProbeFailure(channelId, ackError);
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            for (JsonNode event : client.poll(control.sessionId(), control.token())) {
                JsonNode data = event.path("plugindata").path("data");
                Integer eventError = pluginErrorCode(event);
                if (eventError != null) {
                    throw pinProbeFailure(channelId, eventError);
                }
                if ("joined".equals(data.path("videoroom").asText())) {
                    return; // our pin opened the room — it is ours
                }
            }
        }
        // No joined event and no error within the poll budget — treat as ours but note it:
        // never a squatted verdict on a transport hiccup.
        log.warn("Pin probe of room {} produced no joined event within the poll budget", channelId);
    }

    private static JanusAdminException pinProbeFailure(long channelId, int errorCode) {
        if (errorCode == 433) {
            log.error("Voice room {} is SQUATTED: it exists but rejects our pin "
                    + "(a member pre-created the id with their own pin). Destroy the foreign room "
                    + "on the gateway or restart Janus to recover the channel.", channelId);
            return new JanusAdminException("voice room " + channelId + " exists with a foreign pin (squatted)");
        }
        return new JanusAdminException("voice room " + channelId + " pin probe failed: error " + errorCode);
    }

    /** Videoroom plugin error code ({@code 426}/{@code 429}/{@code 433}… ride the plugindata
     *  asynchronously); null when the frame carries no plugin error. */
    static Integer pluginErrorCode(JsonNode frame) {
        JsonNode data = frame.path("plugindata").path("data");
        if ("error".equals(data.path("videoroom").asText()) && data.path("error_code").isNumber()) {
            return data.path("error_code").asInt();
        }
        return null;
    }

    // --- fire-and-forget path (VoiceControlHandler) ---

    @Override
    public void revokeTokens(long channelId, String username) {
        submitAction(() -> revokeTokensNow(channelId, username));
    }

    /** Synchronous core of {@link #revokeTokens} — package-private for the unit test. */
    void revokeTokensNow(long channelId, String username) {
        List<String> doomed;
        synchronized (tokensByUserChannel) {
            Map<Long, Deque<String>> byChannel = tokensByUserChannel.get(username);
            if (byChannel == null) {
                return;
            }
            doomed = new ArrayList<>(byChannel.remove(channelId));
        }
        for (String token : doomed) {
            try {
                client.removeToken(token);
            } catch (RuntimeException failure) {
                log.debug("Kick remove_token failed (ignored): {}", failure.getMessage());
            }
        }
    }

    @Override
    public void forceKick(long channelId, String username) {
        submitAction(() -> withRoomPublishers(channelId, (publishers, control) -> {
            for (JsonNode publisher : matchingPublishers(publishers, username)) {
                JsonNode response = kickPublisher(control, channelId, publisher.path("id").asLong());
                logResult("force-kick " + username + " from room " + channelId, response);
            }
        }));
    }

    @Override
    public void moderateAudio(long channelId, String username, boolean mute) {
        submitAction(() -> withRoomPublishers(channelId, (publishers, control) -> {
            String secret = roomSecret(channelId);
            for (JsonNode publisher : matchingPublishers(publishers, username)) {
                String audioMid = audioMidOf(publisher);
                if (audioMid == null) {
                    log.debug("Moderate {} in room {}: no published audio stream, skipping", username, channelId);
                    continue;
                }
                Map<String, Object> moderate = new HashMap<>();
                moderate.put("request", "moderate");
                moderate.put("room", channelId);
                moderate.put("id", publisher.path("id").asLong());
                moderate.put("mid", audioMid);
                moderate.put("mute", mute);
                moderate.put("secret", secret);
                JsonNode response = client.message(control.sessionId(), control.handleId(), moderate, control.token());
                logResult((mute ? "server-mute " : "server-unmute ") + username + " in room " + channelId, response);
            }
        }));
    }

    @Override
    public Map<Long, List<String>> tokenHoldersByChannel() {
        Map<Long, java.util.TreeSet<String>> byChannel = new HashMap<>();
        synchronized (tokensByUserChannel) {
            for (Map.Entry<String, Map<Long, Deque<String>>> user : tokensByUserChannel.entrySet()) {
                for (Long channelId : user.getValue().keySet()) {
                    byChannel.computeIfAbsent(channelId, channel -> new java.util.TreeSet<>())
                            .add(user.getKey());
                }
            }
        }
        Map<Long, List<String>> snapshot = new HashMap<>();
        for (Map.Entry<Long, java.util.TreeSet<String>> room : byChannel.entrySet()) {
            snapshot.put(room.getKey(), List.copyOf(room.getValue()));
        }
        return snapshot;
    }

    @Override
    public void kickUnknownPublishers(long channelId, java.util.Set<String> knownUsernames) {
        submitAction(() -> kickUnknownPublishersNow(channelId, knownUsernames));
    }

    /**
     * H9, the display-spoof countermeasure: the forced layer matches publishers by
     * {@code display}, which the CLIENT picks freely — a rogue joining with a garbage display is
     * invisible to {@code moderateAudio} and impersonates whoever's name it copies. Every
     * publisher whose display resolves to NEITHER a live member of the room (tracker ∪ token
     * holders) NOR our own control display is a ghost with no legitimate identity: it is kicked
     * by publisher id on the spot (id-based kick needs no display match). Synchronous core —
     * package-private for the unit test.
     */
    void kickUnknownPublishersNow(long channelId, java.util.Set<String> knownUsernames) {
        withRoomPublishers(channelId, (publishers, control) -> {
            for (JsonNode publisher : publishers) {
                String display = publisher.path("display").asText(null);
                if (display == null || display.equals(CONTROL_DISPLAY)
                        || knownUsernames.contains(display)) {
                    continue;
                }
                JsonNode response = kickPublisher(control, channelId, publisher.path("id").asLong());
                logResult("ghost-publisher kick '" + display + "' (id "
                        + publisher.path("id").asLong() + ") from room " + channelId, response);
            }
        });
    }

    @Override
    public void kickVideoPublishers(long channelId, java.util.Set<String> knownUsernames) {
        submitAction(() -> kickVideoPublishersNow(channelId, knownUsernames));
    }

    /**
     * M8, the AFK video ban's forced layer (security round 7): every KNOWN publisher of the
     * room with a live video stream is kicked whole — the plugin has no per-stream forced
     * video mute, so the full publisher teardown is the only forced form (the honest client
     * stops its own share cooperatively; ghosts stay with the ghost sweeper). Synchronous
     * core — package-private for the unit test.
     */
    void kickVideoPublishersNow(long channelId, java.util.Set<String> knownUsernames) {
        withRoomPublishers(channelId, (publishers, control) -> {
            for (JsonNode publisher : publishers) {
                String display = publisher.path("display").asText(null);
                if (display == null || display.equals(CONTROL_DISPLAY)
                        || !knownUsernames.contains(display) || videoMidOf(publisher) == null) {
                    continue;
                }
                JsonNode response = kickPublisher(control, channelId, publisher.path("id").asLong());
                logResult("afk-video kick " + display + " from room " + channelId, response);
            }
        });
    }

    @Override
    public void destroyRoom(long channelId) {
        submitAction(() -> destroyRoomNow(channelId));
    }

    /** Synchronous core of {@link #destroyRoom} — package-private for the unit test. */
    void destroyRoomNow(long channelId) {
        withControlHandle(control -> {
            Map<String, Object> destroy = new HashMap<>();
            destroy.put("request", "destroy");
            destroy.put("room", channelId);
            destroy.put("secret", roomSecret(channelId));
            JsonNode response = client.message(control.sessionId(), control.handleId(), destroy, control.token());
            logResult("destroy room " + channelId, response);
        });
    }

    // --- control-session plumbing ---

    /** Sends the admin-secret plugin kick for one publisher id (id-based — no display match). */
    private JsonNode kickPublisher(ControlSession control, long channelId, long publisherId) {
        Map<String, Object> kick = new HashMap<>();
        kick.put("request", "kick");
        kick.put("room", channelId);
        kick.put("id", publisherId);
        kick.put("secret", roomSecret(channelId));
        return client.message(control.sessionId(), control.handleId(), kick, control.token());
    }

    /** An attached-but-not-joined videoroom handle plus the throwaway token that opened it. */
    private record ControlSession(long sessionId, long handleId, String token) {}

    @FunctionalInterface
    private interface HandleAction {
        void run(ControlSession control);
    }

    /** Action over an existing room: the publishers snapshot from the passive join's {@code joined} event. */
    @FunctionalInterface
    private interface PublisherAction {
        void run(JsonNode publishers, ControlSession control);
    }

    /** Creates an ephemeral session + videoroom handle, runs the action, always destroys both. */
    private void withControlHandle(HandleAction action) {
        String token = mintToken();
        Long sessionId = null;
        try {
            JsonNode added = client.addToken(token);
            if (isJanusError(added)) {
                throw new JanusAdminException("control add_token failed: " + errorText(added));
            }
            sessionId = client.createSession(token);
            long handleId = client.attach(sessionId, token);
            action.run(new ControlSession(sessionId, handleId, token));
        } finally {
            if (sessionId != null) {
                try {
                    client.destroySession(sessionId, token);
                } catch (RuntimeException ignored) {
                    // best-effort: Janus reaps idle sessions
                }
                try {
                    client.removeToken(token);
                } catch (RuntimeException ignored) {
                    // best-effort
                }
            }
        }
    }

    /**
     * Passive-joins the room (pin required — the server derives it), awaits the {@code joined} event
     * and hands its {@code publishers} array to the action. Never throws at the caller: exceptions
     * bubble to {@link #submitAction}'s WARN.
     */
    private void withRoomPublishers(long channelId, PublisherAction action) {
        withControlHandle(control -> {
            Map<String, Object> join = new HashMap<>();
            join.put("request", "join");
            join.put("room", channelId);
            join.put("ptype", "publisher");
            join.put("display", CONTROL_DISPLAY);
            join.put("pin", roomPin(channelId));
            JsonNode joinAck = client.message(control.sessionId(), control.handleId(), join, control.token());
            if (isJanusError(joinAck)) {
                throw new JanusAdminException("control join failed: " + errorText(joinAck));
            }
            JsonNode joined = awaitJoined(control);
            if (joined == null) {
                throw new JanusAdminException("control join produced no joined event (room " + channelId + ")");
            }
            action.run(joined.path("publishers"), control);
        });
    }

    /** Polls for the {@code videoroom:"joined"} event (join is async — the answer arrives via long-poll). */
    private JsonNode awaitJoined(ControlSession control) {
        for (int attempt = 0; attempt < 3; attempt++) {
            for (JsonNode event : client.poll(control.sessionId(), control.token())) {
                JsonNode data = event.path("plugindata").path("data");
                if ("joined".equals(data.path("videoroom").asText())) {
                    return data;
                }
            }
        }
        return null;
    }

    private boolean roomExists(ControlSession control, long channelId) {
        JsonNode exists = client.message(control.sessionId(), control.handleId(),
                Map.of("request", "exists", "room", channelId), control.token());
        return !isJanusError(exists) && exists.path("plugindata").path("data").path("exists").asBoolean(false);
    }

    private void submitAction(Runnable action) {
        actionExecutor.execute(() -> {
            try {
                action.run();
            } catch (RuntimeException failure) {
                log.warn("Janus forced action failed: {}", failure.getMessage());
            }
        });
    }

    private void logResult(String what, JsonNode response) {
        if (isJanusError(response)) {
            log.warn("{} rejected by Janus: {}", what, errorText(response));
        } else {
            log.info("{} ok", what);
        }
    }

    // --- pure helpers (package-private static for the unit test) ---

    /**
     * Publishers whose display equals the target — ALL sessions of the user (multi-tab), mirroring
     * {@code VoiceMembershipTracker.kick}, which also force-exits every session of a username.
     */
    static List<JsonNode> matchingPublishers(JsonNode publishers, String username) {
        List<JsonNode> matched = new ArrayList<>();
        if (publishers == null || !publishers.isArray()) {
            return matched;
        }
        for (JsonNode publisher : publishers) {
            if (username != null && username.equals(publisher.path("display").asText(null))) {
                matched.add(publisher);
            }
        }
        return matched;
    }

    /** Mid of the publisher's first audio stream — the target of videoroom {@code moderate}; null when none. */
    static String audioMidOf(JsonNode publisher) {
        for (JsonNode stream : publisher.path("streams")) {
            if ("audio".equals(stream.path("type").asText())) {
                String mid = stream.path("mid").asText(null);
                return (mid == null || mid.isBlank()) ? null : mid;
            }
        }
        return null;
    }

    /** Mid of the publisher's first video stream — the AFK video ban's probe; null when none. */
    static String videoMidOf(JsonNode publisher) {
        for (JsonNode stream : publisher.path("streams")) {
            if ("video".equals(stream.path("type").asText())) {
                String mid = stream.path("mid").asText(null);
                return (mid == null || mid.isBlank()) ? null : mid;
            }
        }
        return null;
    }

    static boolean isJanusError(JsonNode response) {
        return response != null && "error".equals(response.path("janus").asText());
    }

    static String errorText(JsonNode response) {
        return response.path("error").path("reason").asText("unknown");
    }

    private String roomSecret(long channelId) {
        return hmacSha256(properties.adminSecret(), SECRET_DOMAIN + channelId);
    }

    private static String mintToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Deterministic per-channel credential: pin (join) and secret (admin) differ by domain prefix. */
    static String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception failure) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", failure);
        }
    }
}
