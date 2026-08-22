package oak.server.dto;

/**
 * One attachment of a message on the way OUT: the display file name plus a freshly-signed
 * presigned GET URL. The MinIO {@code key} is deliberately absent — the client has no use for
 * it (message images are not disk-cached; the URL is re-signed on every fetch, exactly like
 * the former scalar {@code attachmentUrl}).
 */
public record AttachmentResponse(
        String fileName,
        String url
) {
}
