package oak.server.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Stand-in {@link JanusVoiceAccessService} used when the Janus Admin API is disabled (the test
 * profile). Deterministic constants keep {@code RoomController} structurally testable; the real
 * token/pin flow is exercised only by the end-to-end smoke test.
 */
@Service
@ConditionalOnProperty(prefix = "oak.janus", name = "admin-enabled", havingValue = "false", matchIfMissing = true)
public class NoopJanusVoiceAccessService implements JanusVoiceAccessService {

    static final String NOOP_TOKEN = "test-token";
    static final String NOOP_PIN = "test-pin";

    @Override
    public String issueVoiceToken(String username, long channelId) {
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
        // No-op.
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
        return Map.of();
    }

    @Override
    public void destroyRoom(long channelId) {
        // No-op.
    }

    @Override
    public void kickUnknownPublishers(long channelId, java.util.Set<String> knownUsernames) {
        // No-op.
    }
}
