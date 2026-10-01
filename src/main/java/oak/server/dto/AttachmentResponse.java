package oak.server.dto;

/**
 * One attachment of a message on the way OUT: the display file name, a freshly-signed presigned
 * GET URL (re-signed on every response, never ages out), and the stable MinIO {@code key}. The
 * key is the attachment's IDENTITY: the URL changes per fetch and list indices shift when an
 * attachment is edited away, but the key is stable forever — the edit flow addresses
 * keep/remove by it, and a future image cache would key on it too.
 *
 * <p>The three video-preview fields mirror {@link AttachmentRef} out to the client:
 * {@code thumbnailKey} (the JPEG's stable identity — the client's image cache keys on it) and
 * {@code thumbnailUrl} (its own presigned GET, re-signed per fetch like the main URL; null when
 * the video has no preview), plus {@code durationMillis} for the card's duration badge. All
 * three are null for non-video attachments and for videos sent before the preview feature.
 *
 * <p>{@code width}/{@code height} mirror {@link AttachmentRef} out: the media's intrinsic
 * pixel dimensions, so the client reserves the exact layout box before the bytes arrive.
 * Null for rows sent before the field existed.
 *
 * <p>{@code sizeBytes} mirrors {@link AttachmentRef} out (the sender-measured object size,
 * shown by the client's plain-file card). Null for rows sent before the field existed.
 */
public record AttachmentResponse(
        String fileName,
        String url,
        String key,
        String thumbnailKey,
        String thumbnailUrl,
        Long durationMillis,
        Integer width,
        Integer height,
        Long sizeBytes
) {
}
