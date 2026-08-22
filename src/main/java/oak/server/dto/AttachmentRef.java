package oak.server.dto;

import jakarta.validation.constraints.Size;

/**
 * One attachment of a message, as the client sends it: the MinIO object key obtained from
 * {@code POST /api/attachments} (the client uploaded the bytes there beforehand) plus the
 * display file name. Doubles as the persisted shape inside the {@code messages.attachments}
 * JSON column (see {@code AttachmentListCodec}) — the wire element and the stored row can
 * never drift apart. Server-side validation of a non-blank {@code key} happens in
 * {@code MessageService} (a 400, not a bean-validation error, to match the
 * content-or-attachment rule's style).
 */
public record AttachmentRef(
        @Size(max = 512) String key,
        @Size(max = 255) String fileName
) {
}
