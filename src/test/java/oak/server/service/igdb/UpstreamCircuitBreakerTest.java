package oak.server.service.igdb;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbound circuit breaker's pure policy (the blocked-hosting report,
 * 2026-11-19): three consecutive failures park the door, the cooldown doubles
 * per re-opening up to the cap, expiry lets one probe through, and the first
 * success wipes everything.
 */
class UpstreamCircuitBreakerTest {

    /** A hand-driven clock — the cooldown windows advance on command. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.ofEpochMilli(0);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }
    }

    @Test
    void threeConsecutiveFailuresOpenTheBaseCooldown() {
        MutableClock clock = new MutableClock();
        UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(clock);

        assertNull(breaker.onFailure());
        assertFalse(breaker.isOpen());
        assertNull(breaker.onFailure());
        assertFalse(breaker.isOpen());
        assertEquals(UpstreamCircuitBreaker.BASE_COOLDOWN_MILLIS, breaker.onFailure());
        assertTrue(breaker.isOpen());
    }

    @Test
    void failuresDuringAnOpenParkDoNotExtendIt() {
        MutableClock clock = new MutableClock();
        UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(clock);
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        clock.advanceMillis(10_000);

        // The caller fast-fails before reaching the network while open, but a
        // raced in-flight failure must not stack a new cooldown on top.
        assertNull(breaker.onFailure());
        assertEquals(UpstreamCircuitBreaker.BASE_COOLDOWN_MILLIS - 10_000, breaker.cooldownLeftMillis());
    }

    @Test
    void theCooldownDoublesPerReopeningUpToTheCap() {
        MutableClock clock = new MutableClock();
        UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(clock);
        assertEquals(UpstreamCircuitBreaker.BASE_COOLDOWN_MILLIS, trip(breaker, clock));      // 1 min
        assertEquals(UpstreamCircuitBreaker.BASE_COOLDOWN_MILLIS * 2, trip(breaker, clock));  // 2 min
        assertEquals(UpstreamCircuitBreaker.BASE_COOLDOWN_MILLIS * 4, trip(breaker, clock));  // 4 min
        assertEquals(UpstreamCircuitBreaker.BASE_COOLDOWN_MILLIS * 8, trip(breaker, clock));  // 8 min — still doubling
        assertEquals(UpstreamCircuitBreaker.MAX_COOLDOWN_MILLIS, trip(breaker, clock));       // 16 min would exceed 15
        assertEquals(UpstreamCircuitBreaker.MAX_COOLDOWN_MILLIS, trip(breaker, clock));       // capped
    }

    @Test
    void expiryClosesTheDoorAndASuccessResetsTheWholeState() {
        MutableClock clock = new MutableClock();
        UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(clock);
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        assertTrue(breaker.isOpen());

        clock.advanceMillis(UpstreamCircuitBreaker.BASE_COOLDOWN_MILLIS + 1);
        assertFalse(breaker.isOpen()); // the probe goes through

        assertEquals(3, breaker.onSuccess()); // was broken — the comeback logs
        clock.advanceMillis(0);
        assertNull(breaker.onFailure());
        assertNull(breaker.onFailure());
        assertEquals(UpstreamCircuitBreaker.BASE_COOLDOWN_MILLIS, breaker.onFailure()); // streak started over
    }

    /** Opens the breaker (three failures), waits the cooldown out — the next failures re-open it.
     *  The cooldown may land on ANY of the three calls (a re-open can fire on
     *  the first failure after expiry), so the MAX non-null answer is the trip's. */
    private static Long trip(UpstreamCircuitBreaker breaker, MutableClock clock) {
        Long openedFor = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Long cooldown = breaker.onFailure();
            if (cooldown != null) {
                openedFor = cooldown;
            }
        }
        clock.advanceMillis(breaker.cooldownLeftMillis() + 1);
        return openedFor;
    }
}
