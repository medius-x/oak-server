package oak.server.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /api/channels/{channelId}/discussions — start a discussion on one message of that
 * channel. The message must live in the same channel (cross-channel ids 404, no leak).
 *
 * <p><b>Lazy creation (user rule):</b> a bare {@code messageId} still creates the empty
 * discussion immediately, but the CLIENT never sends that — "Start discussion" only opens a
 * local draft, and the first reply carries {@code content} (and optional attachments):
 * the discussion is created (or the existing one reused on conflict — the message is never
 * lost to a 409) WITH that first message in one call. The response then reports
 * {@code replyCount == 1}.
 */
public record CreateDiscussionRequest(
        @NotNull Long messageId,
        @Size(max = 4000) String content,
        List<AttachmentRef> attachments,
        @Size(max = 64) String idempotencyKey
) {

    /** The lazy-create marker: the draft's first reply rides along (content or attachments
     *  present) — the discussion is created WITH this message, and a create conflict reuses
     *  the existing thread instead of 409-ing the message away. */
    public boolean hasFirstMessage() {
        return (content != null && !content.isBlank()) || (attachments != null && !attachments.isEmpty());
    }
}
