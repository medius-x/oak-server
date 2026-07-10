package oak.server.dto;

import java.time.Instant;

/**
 * Flattened message view — author resolved to id + username, no lazy graph leaked. When the
 * message has an attachment, {@code attachmentUrl} is a freshly-signed presigned GET URL (built
 * by {@code MessageService}); otherwise both attachment fields are null. Built by the service so
 * the URL is re-signed on every fetch (never ages out).
 */
public record MessageResponse(
        Long id,
        Long channelId,
        Long authorId,
        String authorUsername,
        String content,
        Instant createdAt,
        String attachmentFileName,
        String attachmentUrl
) {
}
