package oak.server.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Message edit body. {@code content} may be blank when attachments REMAIN on the message
 * (removing the last attachment of an attachment-only message while clearing the text is not a
 * legal edit — the content-or-attachment rule is enforced on the RESULTING message in
 * {@code MessageService.edit}, mirroring create/forward; a text-only message still cannot be
 * blanked).
 *
 * <p>{@code attachments} edits the attachment list Discord-style (remove-only): null = leave
 * the attachments untouched (the plain text edit); a list = REPLACE with exactly this list,
 * which must be a SUBSET of the currently attached keys — no new keys can appear through an
 * edit (adding files is a new message), a foreign or duplicated key is a 400. The MinIO objects
 * of removed attachments are never deleted — intra-instance forwards share the keys.
 */
public record EditMessageRequest(
        @Size(max = 4000) String content,
        List<AttachmentRef> attachments
) {
}
