package oak.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6: the per-ACCOUNT window blocks at its cap (from any address) and is cleared by a
 * successful login; the per-ADDRESS window blocks across accounts at its own, higher cap
 * and is drained by time alone. The 10-minute expiry is the same head-trim
 * {@link DirectContactGate} already exercises — deliberately no clock abstraction, so the
 * caps are the behavior under test.
 */
class LoginRateLimiterTest {

    private final LoginRateLimiter limiter = new LoginRateLimiter();

    @Test
    void accountBlocksAtItsCapAndASuccessClearsIt() {
        for (int failure = 0; failure < LoginRateLimiter.ACCOUNT_FAILURE_CAP; failure++) {
            assertFalse(limiter.isBlocked("alice", "10.0.0.1"), "below the cap the door is open");
            limiter.onFailure("alice", "10.0.0.1");
        }
        assertTrue(limiter.isBlocked("alice", "10.0.0.2"),
                "the ACCOUNT block follows the username, not the address");

        limiter.onSuccess("alice");
        assertFalse(limiter.isBlocked("alice", "10.0.0.1"), "a success clears the account window");
    }

    @Test
    void theAccountBlockLeavesOtherAccountsAlone() {
        for (int failure = 0; failure < LoginRateLimiter.ACCOUNT_FAILURE_CAP; failure++) {
            limiter.onFailure("alice", "10.0.0.1");
        }
        assertFalse(limiter.isBlocked("bob", "10.0.0.1"));
    }

    @Test
    void anAddressBlocksAcrossAccountsAndDrainsOnlyByTime() {
        for (int failure = 0; failure < LoginRateLimiter.ADDRESS_FAILURE_CAP; failure++) {
            limiter.onFailure("spray-" + failure, "10.9.9.9");
        }
        // every individual account failed ONCE — the block is the shared address
        assertTrue(limiter.isBlocked("fresh-target", "10.9.9.9"));
        assertFalse(limiter.isBlocked("fresh-target", "10.9.9.8"));
        // a success clears an ACCOUNT window only — the address block stands
        limiter.onSuccess("spray-0");
        assertTrue(limiter.isBlocked("spray-0", "10.9.9.9"));
    }
}
