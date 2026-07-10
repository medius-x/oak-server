package oak.server.dto;

import jakarta.validation.constraints.Size;

/**
 * Message creation body — sent via REST (variant A). A message needs content, an attachment, or
 * both; the content-or-attachment rule is enforced in {@code MessageService} (so an
 * attachment-only message with empty text is valid — hence no {@code @NotBlank} here).
 */
public record CreateMessageRequest(
        @Size(max = 4000) String content,
        @Size(max = 512) String attachmentKey,
        @Size(max = 255) String attachmentFileName
) {
}
