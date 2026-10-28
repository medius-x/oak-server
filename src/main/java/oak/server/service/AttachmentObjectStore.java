package oak.server.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * Read/delete access to the raw object store — the storage GC needs to LIST what actually sits in
 * the bucket (orphans have no DB row to ask about) and to delete by key; the backup service lists
 * and reads EVERYTHING. Kept apart from {@link AttachmentUrlSigner} (whose job is minting keys
 * and signatures): implemented by the same MinIO bean and by the noop test stand-in (empty
 * listing — nothing is stored under it).
 */
public interface AttachmentObjectStore {

    /** One stored object: its key, size and upload/last-modified time. */
    record StoredObject(String key, long sizeBytes, Instant lastModified) {}

    /** Every object under {@code attachments/} (avatars have their own lifecycle and are never
     *  swept). Empty when the object store is disabled. */
    List<StoredObject> listAttachmentObjects();

    /** Every object under {@code custom-emojis/} — the emoji catalog's own listing: these keys
     *  are invisible to {@link #listAttachmentObjects()}, and the ONLY leak path is an abandoned
     *  upload slot (key minted, row never created), which the night sweep reclaims by checking
     *  the emoji table. Empty when the object store is disabled. */
    List<StoredObject> listCustomEmojiObjects();

    /** Every object under {@code stickers/} — the sticker catalog's listing, the same abandoned
     *  -slot reclaim story as {@link #listCustomEmojiObjects()} against the sticker table.
     *  Empty when the object store is disabled. */
    List<StoredObject> listStickerObjects();

    /** EVERY object of the bucket, all prefixes (attachments, avatars, avatars/originals,
     *  server-avatars, custom-emojis, stickers) — the backup's full listing. Metadata only,
     *  no bytes are read. Empty when the object store is disabled. */
    List<StoredObject> listAllObjects();

    /** Opens one object for reading (the backup streams it into the archive). The caller
     *  owns closing the stream. */
    InputStream openObject(String key) throws IOException;

    /** Uploads one object (the restore leg of the backup). The stream is consumed by the
     *  implementation; {@code sizeBytes} must be the exact remaining length of {@code body}. */
    void putObject(String key, InputStream body, long sizeBytes) throws IOException;

    /** Removes one object; failures are the implementor's to log (a dead object is not fatal). */
    void delete(String key);
}
