package oak.server.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Message creation body — sent via REST (variant A). A message needs content, attachments, or
 * both; the content-or-attachment rule is enforced in {@code MessageService} (so an
 * attachment-only message with empty text is valid — hence no {@code @NotBlank} here). Each
 * {@link AttachmentRef} key comes from {@code POST /api/attachments} (bytes already uploaded);
 * at most {@code MessageService.MAX_ATTACHMENTS} per message, null tolerated as none.
 * {@code replyToId} makes the message a reply to that message (same channel, 404 otherwise).
 */
public record CreateMessageRequest(
        @Size(max = 4000) String content,
        @Size(max = 10) List<AttachmentRef> attachments,
        Long replyToId
) {
}
