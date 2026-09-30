package oak.server.service;

import oak.server.entity.Invite;
import oak.server.entity.User;
import oak.server.repository.InviteRepository;
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
 * The SCHEDULED sweep entry, under exactly the production conditions: no ambient
 * transaction (the RefreshTokenCleanupTest pattern — a self-invocation bug of that very
 * shape once made the purge a silent no-op). Seeds one dead row of each kind (revoked,
 * expired) plus one active invite: only the dead ones may go. The purge COMMITS here, so
 * the seeds are removed in {@code @AfterEach} (the H2 context is shared).
 */
@SpringBootTest
@ActiveProfiles("test")
class InvitesCleanupTest {

    @Autowired
    private InvitesCleanup cleanup;

    @Autowired
    private InviteRepository invites;

    @Autowired
    private UserRepository users;

    private User probe;
    private String revokedSecret;
    private String expiredSecret;
    private String activeSecret;

    @Test
    void scheduledPurgeDeletesRevokedAndExpiredButKeepsActive() {
        String salt = Long.toUnsignedString(System.nanoTime());
        probe = users.save(new User("invites-cleanup-" + salt, "not-a-real-hash"));
        revokedSecret = "revoked-" + salt;
        expiredSecret = "expired-" + salt;
        activeSecret = "active-" + salt;
        Instant now = Instant.now();
        Invite revoked = new Invite(revokedSecret, probe, now.plus(Duration.ofHours(1)));
        revoked.setRevoked(true);
        invites.save(revoked); // "Expire" — unexpired by TTL, dead by the flag
        invites.save(new Invite(expiredSecret, probe, now.minus(Duration.ofMinutes(1))));
        invites.save(new Invite(activeSecret, probe, now.plus(Duration.ofHours(1))));

        cleanup.scheduledPurge();

        assertFalse(invites.findBySecret(revokedSecret).isPresent(), "the revoked invite is purged");
        assertFalse(invites.findBySecret(expiredSecret).isPresent(), "the expired invite is purged");
        assertTrue(invites.findBySecret(activeSecret).isPresent(), "the active invite survives");
    }

    @AfterEach
    void removeSeeds() {
        invites.deleteAll();   // only this test's rows ever remain (all others roll back)
        if (probe != null) {
            users.delete(probe);
        }
    }
}
