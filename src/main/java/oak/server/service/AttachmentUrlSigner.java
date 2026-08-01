package oak.server.service;

/**
 * Mints object keys and signs time-limited MinIO presigned URLs. Backed by
 * {@link MinioAttachmentService} when MinIO is enabled; a noop implementation stands in for the
 * test profile so the context loads without a running MinIO.
 */
public interface AttachmentUrlSigner {

    /** A fresh object key for an upload of {@code fileName} (e.g. {@code attachments/<uuid>/<name>}). */
    String newKey(String fileName);

    /** A fresh object key for an avatar upload of {@code fileName} (e.g. {@code avatars/<uuid>/<name>}). */
    String newAvatarKey(String fileName);

    /** A fresh object key for the original (pre-crop) avatar image (e.g. {@code avatars/originals/<uuid>/<name>}). */
    String newOriginalKey(String fileName);

    /** A fresh object key for the instance (server) icon upload (e.g. {@code server-avatars/<uuid>/<name>}). */
    String newServerAvatarKey(String fileName);

    /** Short-lived presigned PUT URL authorizing an upload of exactly {@code key}. */
    String presignedPutUrl(String key);

    /** Presigned GET URL for downloading {@code key}; re-signed on each call so it never ages out. */
    String presignedGetUrl(String key);

    /**
     * Deletes the object at {@code key} (best-effort — a leftover orphan is harmless). Used to clean
     * up a previous avatar object when a user replaces or removes their avatar.
     */
    void delete(String key);
}
