package oak.server.dto;

import java.time.Instant;
import java.util.List;

/**
 * Flattened message view — author resolved to id + username, no lazy graph leaked. When the
 * message has an attachment, {@code attachmentUrl} is a freshly-signed presigned GET URL (built
 * by {@code MessageService}); otherwise both attachment fields are null. Built by the service so
 * the URL is re-signed on every fetch (never ages out). {@code mentions} is the parsed list of
 * {@code @}-mentions (never null, empty when none) — see {@link MentionResponse}.
 */
public record MessageResponse(
        Long id,
        Long channelId,
        Long authorId,
        String authorUsername,
        String content,
        Instant createdAt,
        String attachmentFileName,
        String attachmentUrl,
        List<MentionResponse> mentions
) {
}
