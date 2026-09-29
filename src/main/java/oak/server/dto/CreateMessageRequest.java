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
 * {@code idempotencyKey} (4th, appended — wire append-only) lets a client whose response was
 * lost to a network blip safely retry the SAME logical send: the server returns the original
 * message instead of creating a duplicate row. Null (legacy clients) = no dedup.
 * {@code stickerId} (5th, appended) references the sticker catalog row to carry; the message
 * stores a denormalized name+key copy, and a sticker satisfies the content-or-attachment
 * rule on its own (sticker-only sends have {@code content: null}). No ATTACH_FILES is
 * required — a sticker is not a file attachment.
 */
public record CreateMessageRequest(
        @Size(max = 4000) String content,
        @Size(max = 10) List<AttachmentRef> attachments,
        Long replyToId,
        @Size(max = 64) String idempotencyKey,
        Long stickerId
) {

    /** Legacy 4-field shape (no sticker) — callers and tests predating stickers. */
    public CreateMessageRequest(String content, List<AttachmentRef> attachments, Long replyToId,
                                String idempotencyKey) {
        this(content, attachments, replyToId, idempotencyKey, null);
    }

    /** Legacy 3-field shape (no idempotency key, no sticker) — old callers and tests. */
    public CreateMessageRequest(String content, List<AttachmentRef> attachments, Long replyToId) {
        this(content, attachments, replyToId, null);
    }
}
