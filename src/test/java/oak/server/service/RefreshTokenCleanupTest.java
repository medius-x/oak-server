package oak.server.service;

import oak.server.entity.RefreshToken;
import oak.server.entity.User;
import oak.server.repository.RefreshTokenRepository;
import oak.server.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SCHEDULED purge entry, not the direct one. {@code AuthFlowIntegrationTest} exercises
 * {@code purgeExpired()} as an external call — through the CGLIB proxy, so its
 * {@code @Transactional} applies and the test is green. In production the scheduler enters
 * {@code scheduledPurge()} via the proxy but then calls {@code purgeExpired()} on {@code this} —
 * a self-invocation that BYPASSES the proxy — and every purge died with
 * {@code TransactionRequiredException} (the cleanup never deleted anything).
 *
 * <p>This test deliberately has NO ambient transaction (no {@code @Transactional}): it calls the
 * scheduled entry under exactly the production conditions, so the regression cannot hide again.
 * Because the purge COMMITS here, the seeds are removed in {@code @AfterEach} — the H2 context is
 * shared with {@code AuthFlowIntegrationTest}, whose cleanup test asserts exact table counts.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenCleanupTest {

    private static final String FAMILY = "family-cleanup-scheduled";

    @Autowired
    private RefreshTokenCleanup cleanup;

    @Autowired
    private RefreshTokenRepository tokens;

    @Autowired
    private UserRepository users;

    private User probe;
    private String expiredHash;
    private String liveHash;

    @Test
    void scheduledPurgeDeletesExpiredTokensInsideItsOwnTransaction() {
        String salt = Long.toUnsignedString(System.nanoTime());
        probe = users.save(new User("cleanup-probe-" + salt, "not-a-real-hash"));
        expiredHash = "expired-" + salt;
        liveHash = "live-" + salt;
        tokens.save(new RefreshToken(probe, expiredHash,
                Instant.now().minus(Duration.ofHours(1)), FAMILY));
        tokens.save(new RefreshToken(probe, liveHash,
                Instant.now().plus(Duration.ofHours(1)), FAMILY));

        // Before the fix this threw TransactionRequiredException before deleting anything.
        cleanup.scheduledPurge();

        assertFalse(tokens.findByTokenHash(expiredHash).isPresent(),
                "the expired token must be purged");
        assertTrue(tokens.findByTokenHash(liveHash).isPresent(),
                "the live token must survive the purge");
    }

    @AfterEach
    void removeSeeds() {
        tokens.deleteAll(tokens.findByFamilyId(FAMILY));   // FK first, then the user
        if (probe != null) {
            users.delete(probe);
        }
    }
}
