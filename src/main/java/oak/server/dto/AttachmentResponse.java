package oak.server.dto;

/**
 * One attachment of a message on the way OUT: the display file name, a freshly-signed presigned
 * GET URL (re-signed on every response, never ages out), and the stable MinIO {@code key}. The
 * key is the attachment's IDENTITY: the URL changes per fetch and list indices shift when an
 * attachment is edited away, but the key is stable forever — the edit flow addresses
 * keep/remove by it, and a future image cache would key on it too.
 */
public record AttachmentResponse(
        String fileName,
        String url,
        String key
) {
}
