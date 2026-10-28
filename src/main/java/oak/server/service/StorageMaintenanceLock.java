package oak.server.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The single mutual-exclusion point of the two heavy storage-maintenance operations — the
 * storage GC ({@link StorageGcService}) and the backup build ({@link BackupService}). Without
 * it a GC pass deleting oldest messages/objects MID-DUMP would leave the archive inconsistent
 * (rows read, objects already swept); a user requirement also asks the two never to run at the
 * same wall-clock time. Both services {@link #tryAcquire()} before doing any work and release
 * in a {@code finally}; the GC's scheduled tick DEFERS (does not latch its day) so the backup
 * finishing inside the catch-up window lets the nightly run still fire.
 */
@Component
public class StorageMaintenanceLock {

    private final ReentrantLock lock = new ReentrantLock();

    /** Non-blocking: the callers translate a miss into a 409 / a deferred tick, never a wait. */
    public boolean tryAcquire() {
        return lock.tryLock();
    }

    /** Only the owner of a successful {@link #tryAcquire()} may call this. */
    public void release() {
        lock.unlock();
    }

    /** Best-effort pre-check for controllers answering 409 BEFORE spawning a worker thread. */
    public boolean isLocked() {
        return lock.isLocked();
    }
}
