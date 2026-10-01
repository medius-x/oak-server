package oak.server.websocket;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AFK sweep's activity clock: the last time each voice member was known ACTIVE
 * (joining a room counts, and clients push {@code /app/voice.activity} heartbeats while
 * their user is active). The server has no view into the media plane — voice activity
 * and system idle are CLIENT-side facts, so this registry is the aggregation point of
 * their reports.
 *
 * <p>Entries are only ever READ for usernames the membership tracker currently reports
 * in a room, so a stale entry after a disconnect is harmless (it is simply never
 * consulted) — no eviction wiring.
 */
@Component
public class VoiceActivityRegistry {

    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

    /** Records that the member is active right now. */
    public void touch(String username) {
        lastActivity.put(username, Instant.now());
    }

    /** When the member was last known active — the epoch (i.e. "never") for an
     *  unreported member, which the sweep treats as idle-since-join. */
    public Instant lastActive(String username) {
        return lastActivity.getOrDefault(username, Instant.EPOCH);
    }
}
