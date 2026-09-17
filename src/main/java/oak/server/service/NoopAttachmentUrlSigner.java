package oak.server.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Stand-in {@link AttachmentUrlSigner} used when MinIO is disabled (the test profile). It still
 * returns object keys so the attachment endpoint is structurally testable, but no real URLs —
 * actual upload/download is exercised only by the end-to-end smoke test.
 */
@Service
@ConditionalOnProperty(prefix = "oak.minio", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopAttachmentUrlSigner implements AttachmentUrlSigner, AttachmentObjectStore {

    @Override
    public String newKey(String fileName) {
        return key("attachments/noop", fileName);
    }

    @Override
    public String newAvatarKey(String fileName) {
        return key("avatars/noop", fileName);
    }

    @Override
    public String newOriginalKey(String fileName) {
        return key("avatars/noop/originals", fileName);
    }

    @Override
    public String newServerAvatarKey(String fileName) {
        return key("server-avatars/noop", fileName);
    }

    private static String key(String prefix, String fileName) {
        String safe = (fileName == null || fileName.isBlank()) ? "file" : fileName;
        return prefix + "/" + UUID.randomUUID() + "/" + safe;
    }

    @Override
    public String presignedPutUrl(String key) {
        return null;
    }

    @Override
    public String presignedGetUrl(String key) {
        return null;
    }

    @Override
    public void delete(String key) {
        // No-op: nothing is actually stored under the noop signer.
    }

    @Override
    public java.util.List<StoredObject> listAttachmentObjects() {
        return java.util.List.of(); // nothing is stored — the orphan sweep is a no-op
    }
}
