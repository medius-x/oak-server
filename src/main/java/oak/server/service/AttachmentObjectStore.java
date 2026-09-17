package oak.server.service;

import java.time.Instant;
import java.util.List;

/**
 * Read/delete access to the raw object store — the storage GC needs to LIST what actually sits in
 * the bucket (orphans have no DB row to ask about) and to delete by key. Kept apart from
 * {@link AttachmentUrlSigner} (whose job is minting keys and signatures): implemented by the same
 * MinIO bean and by the noop test stand-in (empty listing — nothing is stored under it).
 */
public interface AttachmentObjectStore {

    /** One stored object: its key, size and upload/last-modified time. */
    record StoredObject(String key, long sizeBytes, Instant lastModified) {}

    /** Every object under {@code attachments/} (avatars have their own lifecycle and are never
     *  swept). Empty when the object store is disabled. */
    List<StoredObject> listAttachmentObjects();

    /** Removes one object; failures are the implementor's to log (a dead object is not fatal). */
    void delete(String key);
}
