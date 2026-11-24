package oak.server.service.igdb;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The outbound circuit breaker for IGDB-class upstreams (user
 * report 2026-11-19: the hosting may BLOCK or black-hole them — GitHub is
 * already unreachable from the box in question). Timeouts alone still mean
 * every fresh game burns a 5 s connect and a log line FOREVER; the breaker
 * parks the outbound door after {@link #FAILURE_THRESHOLD} consecutive
 * upstream failures and answers instantly while parked.
 *
 * <p>Policy: the first opening parks for {@link #BASE_COOLDOWN_MILLIS}, each
 * re-opening doubles the cooldown up to {@link #MAX_COOLDOWN_MILLIS} (a
 * blocked host is checked less and less often); the first success resets
 * everything. The caller logs the ONE line per transition — per-request
 * noise is exactly what this exists to kill.
 *
 * <p>Thread-safety: guarded by {@code synchronized} on this — the query path
 * holds the lock for two integer reads, never for I/O.
 */
public class UpstreamCircuitBreaker {

    static final int FAILURE_THRESHOLD = 3;
    static final long BASE_COOLDOWN_MILLIS = 60_000;
    static final long MAX_COOLDOWN_MILLIS = 900_000;

    private final Clock clock;
    private int consecutiveFailures = 0;
    private int openingStreak = 0;
    private long openUntilMillis = Long.MIN_VALUE;
    private final Deque<Long> openedAtMillis = new ArrayDeque<>();

    public UpstreamCircuitBreaker(Clock clock) {
        this.clock = clock;
    }

    /** Whether the door is parked RIGHT NOW — the caller must fast-fail. */
    public synchronized boolean isOpen() {
        return clock.millis() < openUntilMillis;
    }

    /** Seconds left in the current park, for the fast-fail message (0 when open by a stale read). */
    public synchronized long cooldownLeftMillis() {
        return Math.max(0, openUntilMillis - clock.millis());
    }

    /**
     * A failure landed. Returns the cooldown the breaker JUST opened for
     * (the caller logs the transition), or {@code null} when this failure
     * did not open anything.
     */
    public synchronized Long onFailure() {
        consecutiveFailures += 1;
        if (consecutiveFailures < FAILURE_THRESHOLD || isOpen()) {
            return null;
        }
        openingStreak += 1;
        long cooldown = Math.min(MAX_COOLDOWN_MILLIS, BASE_COOLDOWN_MILLIS << (openingStreak - 1));
        openUntilMillis = clock.millis() + cooldown;
        openedAtMillis.addLast(clock.millis());
        return cooldown;
    }

    /**
     * A success landed (the upstream answered). Returns the number of
     * failures the recovery wiped — non-zero means "was broken, log the
     * comeback" — and resets the whole state.
     */
    public synchronized int onSuccess() {
        int recoveredFrom = consecutiveFailures;
        consecutiveFailures = 0;
        openingStreak = 0;
        openUntilMillis = Long.MIN_VALUE;
        openedAtMillis.clear();
        return recoveredFrom;
    }

    /** How many times the breaker has opened since the last success (tests). */
    synchronized int openingStreak() {
        return openingStreak;
    }
}
