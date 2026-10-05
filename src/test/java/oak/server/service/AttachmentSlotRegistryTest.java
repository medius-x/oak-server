package oak.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentSlotRegistryTest {

    private final AttachmentSlotRegistry registry = new AttachmentSlotRegistry();

    @Test
    void ownershipFollowsTheMinter() {
        registry.registerMint("attachments/abc/a.png", "alice");
        assertTrue(registry.isOwnedBy("attachments/abc/a.png", "alice"));
        assertFalse(registry.isOwnedBy("attachments/abc/a.png", "bob"));
        assertTrue(registry.isKnownForeignTo("attachments/abc/a.png", "bob"));
        assertFalse(registry.isKnownForeignTo("attachments/abc/a.png", "alice"));
    }

    @Test
    void unknownKeyIsForeignToNobody() {
        // The soft check: a key minted before a restart (or by an older instance) must not
        // block the pending upload — the hard boundary is the prefix check in the caller.
        assertFalse(registry.isKnownForeignTo("attachments/xyz/unknown.png", "bob"));
        assertFalse(registry.isOwnedBy("attachments/xyz/unknown.png", "bob"));
    }

    @Test
    void ownershipExpiresAfterTheTtl() {
        registry.registerMint("attachments/abc/old.png", "alice");
        registry.advanceClockBy(AttachmentSlotRegistry.SLOT_TTL_MILLIS + 1);
        assertFalse(registry.isOwnedBy("attachments/abc/old.png", "alice"));
        assertFalse(registry.isKnownForeignTo("attachments/abc/old.png", "bob"));
    }

    @Test
    void mintQuotaCapsTheRollingWindowPerUser() {
        for (int mint = 0; mint < AttachmentSlotRegistry.MINT_WINDOW_CAP; mint++) {
            assertTrue(registry.tryAcquireMintQuota("alice"));
        }
        assertFalse(registry.tryAcquireMintQuota("alice"));
        assertTrue(registry.tryAcquireMintQuota("bob"), "the window is per-user");
    }

    @Test
    void mintQuotaWindowSlides() {
        for (int mint = 0; mint < AttachmentSlotRegistry.MINT_WINDOW_CAP; mint++) {
            assertTrue(registry.tryAcquireMintQuota("alice"));
        }
        assertFalse(registry.tryAcquireMintQuota("alice"));
        registry.advanceClockBy(AttachmentSlotRegistry.MINT_WINDOW_MILLIS + 1);
        assertTrue(registry.tryAcquireMintQuota("alice"), "the old mints aged out of the window");
    }
}
