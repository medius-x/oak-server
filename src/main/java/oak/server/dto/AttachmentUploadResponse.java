package oak.server.dto;

/**
 * An upload slot: the object {@code key} (referenced later by the message) and a short-lived
 * presigned PUT URL the client uploads the bytes directly to. The URL authorizes only that one
 * key for a few minutes; it carries no MinIO credentials.
 */
public record AttachmentUploadResponse(String key, String uploadUrl) {
}
