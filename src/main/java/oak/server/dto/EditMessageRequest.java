package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Message edit body. Content is required: an edit always carries text (a blank edit is
 * meaningless — attachments are not editable, so "clear everything" is a delete, not an edit).
 * An attachment-only message gains text on edit; the attachment itself stays untouched.
 */
public record EditMessageRequest(
        @NotBlank @Size(max = 4000) String content
) {
}
