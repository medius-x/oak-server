package oak.server.dto;

import java.time.Instant;
import java.util.List;

/**
 * Flattened message view — author resolved to id + username, no lazy graph leaked. When the
 * message has an attachment, {@code attachmentUrl} is a freshly-signed presigned GET URL (built
 * by {@code MessageService}); otherwise both attachment fields are null. Built by the service so
 * the URL is re-signed on every fetch (never ages out). {@code mentions} is the parsed list of
 * {@code @}-mentions (never null, empty when none) — see {@link MentionResponse}.
 * {@code editedAt} is null until the message is edited (the client renders an "(edited)" marker).
 * {@code reactions} is the aggregated emoji state (never null, empty when none) — see
 * {@link ReactionSummary}; an edit push MUST carry the current snapshot or clients would wipe
 * the rendered chips when applying the edited message.
 * {@code reply} is the quote this message renders when it is a reply (null = not a reply) — see
 * {@link ReplyInfo}; a reply's author-mention is synthesized into {@code mentions} (offsets -1).
 * {@code forward} is the origin card when this message is a forward (null = not a forward) — see
 * {@link ForwardInfo}; the message's own author is the forwarder.
 * Field order is wire-stable — new fields append at the end only, paired with the client mirror.
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
        List<MentionResponse> mentions,
        Instant editedAt,
        List<ReactionSummary> reactions,
        ReplyInfo reply,
        ForwardInfo forward
) {
}
