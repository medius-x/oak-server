package oak.server.service.igdb;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-user budget of the /api/games endpoints (lookup + card + confirm share it):
 * a rolling 30-requests-per-minute window in the {@code LoginRateLimiter} deque idiom.
 * The IGDB quota (4 requests per SECOND, per instance!) is a shared resource — a
 * hostile or buggy client must not be able to burn it with a lookup spray, and the
 * match cache alone cannot stop novel keys. Memory-bounded the same way: past
 * {@link #MAP_CAP} distinct usernames the stale windows are pruned, and a spray that
 * still floods the cap resets the map wholesale.
 */
@Component
public class IgdbLookupRateLimiter {

    public static final long WINDOW_MILLIS = 60 * 1000L;
    public static final int CAP = 30;
    private static final int MAP_CAP = 4096;

    private final Map<String, Deque<Long>> requestsByUsername = new ConcurrentHashMap<>();

    /** Records this request and answers whether it fits the window (false = 429). */
    public boolean tryAcquire(String username) {
        if (username == null) {
            return false;
        }
        Deque<Long> window = requestsByUsername.computeIfAbsent(username, ignored -> new ArrayDeque<>());
        synchronized (window) {
            long now = System.currentTimeMillis();
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= CAP) {
                return false; // blocked — and a blocked request adds nothing (sustained rate = the cap)
            }
            if (requestsByUsername.size() >= MAP_CAP) {
                pruneStale(requestsByUsername);
            }
            window.addLast(now);
            return true;
        }
    }

    /** Drops fully-expired windows; a spray that still floods the cap resets the map
     *  wholesale — the LoginRateLimiter availability-over-precision trade-off. */
    private static void pruneStale(Map<String, Deque<Long>> windows) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Deque<Long>>> entries = windows.entrySet().iterator();
        while (entries.hasNext()) {
            Deque<Long> window = entries.next().getValue();
            synchronized (window) {
                while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                    window.pollFirst();
                }
                if (window.isEmpty()) {
                    entries.remove();
                }
            }
        }
        if (windows.size() >= MAP_CAP) {
            windows.clear();
        }
    }
}
