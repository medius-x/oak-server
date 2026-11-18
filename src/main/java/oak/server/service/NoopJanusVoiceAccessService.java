package oak.server.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in {@link JanusVoiceAccessService} used when the Janus Admin API is disabled (the test
 * profile). Deterministic constants keep {@code RoomController} structurally testable; the real
 * token/pin flow is exercised only by the end-to-end smoke test.
 *
 * <p>The token REGISTRY is faithfully in-memory (V18, 2026-11-18): {@code RoomController}'s
 * user-limit gate reads it — issued tokens occupy seats, revocations free them — so integration
 * tests express "holds a room token" by simply calling {@code GET /room}, exactly like the real
 * gateway flow.
 */
@Service
@ConditionalOnProperty(prefix = "oak.janus", name = "admin-enabled", havingValue = "false", matchIfMissing = true)
public class NoopJanusVoiceAccessService implements JanusVoiceAccessService {

    static final String NOOP_TOKEN = "test-token";
    static final String NOOP_PIN = "test-pin";

    /** channelId &rarr; usernames holding a "minted" token — the mirror of the real registry. */
    private final Map<Long, Set<String>> tokenHolders = new ConcurrentHashMap<>();

    @Override
    public String issueVoiceToken(String username, long channelId) {
        tokenHolders.computeIfAbsent(channelId, channel -> ConcurrentHashMap.newKeySet()).add(username);
        return NOOP_TOKEN;
    }

    @Override
    public String roomPin(long channelId) {
        return NOOP_PIN;
    }

    @Override
    public void ensureRoom(long channelId) {
        // No-op: no real gateway in tests.
    }

    @Override
    public void revokeTokens(long channelId, String username) {
        Set<String> holders = tokenHolders.get(channelId);
        if (holders != null) {
            holders.remove(username);
        }
    }

    @Override
    public void forceKick(long channelId, String username) {
        // No-op.
    }

    @Override
    public void moderateAudio(long channelId, String username, boolean mute) {
        // No-op.
    }

    @Override
    public Map<Long, List<String>> tokenHoldersByChannel() {
        Map<Long, List<String>> snapshot = new HashMap<>();
        for (Map.Entry<Long, Set<String>> room : tokenHolders.entrySet()) {
            if (!room.getValue().isEmpty()) {
                snapshot.put(room.getKey(), List.copyOf(room.getValue()));
            }
        }
        return snapshot;
    }

    @Override
    public void destroyRoom(long channelId) {
        // No-op.
    }

    @Override
    public void kickUnknownPublishers(long channelId, java.util.Set<String> knownUsernames) {
        // No-op.
    }

    @Override
    public void kickVideoPublishers(long channelId, java.util.Set<String> knownUsernames) {
        // No-op.
    }

    @Override
    public RoomProbe probeRoom(long channelId) {
        // The test-profile world: rooms never vanish, the sweeps never heal anything.
        return RoomProbe.EXISTS;
    }

    @Override
    public void revokeTokensForUser(String username) {
        for (Set<String> holders : tokenHolders.values()) {
            holders.remove(username);
        }
    }
}
