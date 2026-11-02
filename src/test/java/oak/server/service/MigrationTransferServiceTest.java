package oak.server.service;

import oak.server.service.MigrationTransferService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-memory single-slot migration token: issue/validate/revoke, displacement of the
 * previous token, the lazy TTL rejection, and the one-transfer-at-a-time claim.
 */
class MigrationTransferServiceTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-02T12:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        void advanceBy(Duration step) {
            now = now.plus(step);
        }
    }

    @Test
    void tokenValidatesWithinItsTtlAndDiesAfterIt() {
        MigrationTransferService service = new MigrationTransferService();
        MutableClock clock = new MutableClock();
        service.clock = clock;

        MigrationTransferService.Issued issued = service.issue();
        assertTrue(service.isValid(issued.token()));
        assertFalse(service.isValid("forged-token"));

        clock.advanceBy(Duration.ofHours(2).plusSeconds(1));
        assertFalse(service.isValid(issued.token()), "the token must expire with its TTL");
    }

    @Test
    void issuingDisplacesThePreviousTokenAndRevocationKillsTheCurrentOne() {
        MigrationTransferService service = new MigrationTransferService();

        MigrationTransferService.Issued first = service.issue();
        MigrationTransferService.Issued second = service.issue();
        assertNotEquals(first.token(), second.token());
        assertFalse(service.isValid(first.token()), "a new issue displaces the old token");
        assertTrue(service.isValid(second.token()));

        service.revoke();
        assertFalse(service.isValid(second.token()));
        // Revocation is idempotent.
        service.revoke();
    }

    @Test
    void theTransferSlotIsSingleOccupancyAndBalancedByEndTransfer() {
        MigrationTransferService service = new MigrationTransferService();
        MigrationTransferService.Issued issued = service.issue();

        assertTrue(service.beginTransfer(issued.token()));
        assertFalse(service.beginTransfer(issued.token()),
                "a second concurrent transfer must be refused");
        assertFalse(service.beginTransfer("forged-token"), "a forged token never claims the slot");

        service.endTransfer();
        assertTrue(service.beginTransfer(issued.token()), "the released slot is claimable again");
    }
}
