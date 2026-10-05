package oak.server.service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory bookkeeping for upload slots MINED by this server instance: which user a key
 * was issued to, and how fast they are minting. Three gates ride on it (security round 3):
 * <ul>
 *   <li><b>Avatar/server-icon confirm</b> — a key may only be bound after being minted FOR
 *       that user. Without this, confirm accepted ANY key (another user's avatar, an emoji
 *       file read from the public catalogs) and the subsequent replace/remove DELETED that
 *       foreign MinIO object — a low-privilege vandalism primitive.</li>
 *   <li><b>Message attachments</b> — keys outside the {@code attachments/} namespace are
 *       rejected outright, and a key known to be minted for SOMEONE ELSE is rejected (soft
 *       check: an unknown key still passes, so a pending upload survives a server restart —
 *       the prefix check is the hard boundary there).</li>
 *   <li><b>Mint rate limit</b> — N slots per rolling window per user: attachment minting
 *       required no permission at all and no quota, so any account could park unlimited
 *       bytes in MinIO for the orphan-GC grace period (disk-exhaustion DoS).</li>
 * </ul>
 * Slots are forgotten after {@link #SLOT_TTL_MILLIS} (an upload that never got referenced
 * is reclaimed by the nightly GC anyway) and the maps are capped, degrading to "unknown
 * key" — never an outage — when saturated. In-memory by design: like the message
 * idempotency cache, a restart only loosens the soft owner check for in-flight uploads.
 */
@Component
public class AttachmentSlotRegistry {

    /** How long a minted key stays attributed to its owner. */
    static final long SLOT_TTL_MILLIS = 24 * 60 * 60 * 1000L;
    /** Rolling mint window per user. */
    static final long MINT_WINDOW_MILLIS = 10 * 60 * 1000L;
    /** Minted slots per user per window — a message carries at most 10 attachments. */
    static final int MINT_WINDOW_CAP = 60;
    /** Backstop against unbounded growth; a full sweep keeps the newest slots. */
    private static final int MAX_TRACKED_SLOTS = 10_000;

    private record Slot(String owner, long mintedAt) {
    }

    private final Map<String, Slot> slotsByOwner = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> mintWindows = new ConcurrentHashMap<>();
    private long clockOffset;

    /** Records a freshly minted key for the user. Call on EVERY slot mint. */
    public void registerMint(String key, String username) {
        sweepIfSaturated();
        slotsByOwner.put(key, new Slot(username, now()));
    }

    /** True when the key was minted by THIS instance for THIS user. */
    public boolean isOwnedBy(String key, String username) {
        Slot slot = slotsByOwner.get(key);
        return slot != null && slot.owner().equals(username) && !expired(slot);
    }

    /** True when the key is known and was minted for someone else (reject: smuggling). */
    public boolean isKnownForeignTo(String key, String username) {
        Slot slot = slotsByOwner.get(key);
        return slot != null && !slot.owner().equals(username) && !expired(slot);
    }

    /** Consumes one mint from the user's rolling window; false = rate limited. */
    public boolean tryAcquireMintQuota(String username) {
        long timestamp = now();
        Deque<Long> window = mintWindows.computeIfAbsent(username, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && timestamp - window.peekFirst() >= MINT_WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= MINT_WINDOW_CAP) {
                return false;
            }
            window.addLast(timestamp);
            return true;
        }
    }

    private boolean expired(Slot slot) {
        return now() - slot.mintedAt() >= SLOT_TTL_MILLIS;
    }

    private void sweepIfSaturated() {
        if (slotsByOwner.size() < MAX_TRACKED_SLOTS) {
            return;
        }
        long timestamp = now();
        Iterator<Map.Entry<String, Slot>> iterator = slotsByOwner.entrySet().iterator();
        while (iterator.hasNext()) {
            if (timestamp - iterator.next().getValue().mintedAt() >= SLOT_TTL_MILLIS) {
                iterator.remove();
            }
        }
        // Still saturated with live slots: drop the bookkeeping rather than grow unbounded —
        // the worst case is the soft owner check degrading to "unknown key".
        if (slotsByOwner.size() >= MAX_TRACKED_SLOTS) {
            slotsByOwner.clear();
        }
    }

    private long now() {
        return System.currentTimeMillis() + clockOffset;
    }

    /** Test seam: rewind the clock so TTL windows can be exercised without sleeping. */
    void advanceClockBy(long millis) {
        clockOffset += millis;
    }

    /** Test seam: forget a single key (simulating a pre-restart mint). */
    void forget(String key) {
        slotsByOwner.remove(key);
    }
}
