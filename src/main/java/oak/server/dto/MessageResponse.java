package oak.server.dto;

import java.time.Instant;
import java.util.List;

/**
 * Flattened message view — author resolved to id + username, no lazy graph leaked.
 * {@code attachments} is the message's file list (never null, empty when none) — see
 * {@link AttachmentResponse}; each URL is a freshly-signed presigned GET, re-signed on every
 * fetch so it never ages out. {@code mentions} is the parsed list of {@code @}-mentions (never
 * null, empty when none) — see {@link MentionResponse}. {@code editedAt} is null until the
 * message is edited (the client renders an "(edited)" marker). {@code reactions} is the
 * aggregated emoji state (never null, empty when none) — see {@link ReactionSummary}; an edit
 * push MUST carry the current snapshot or clients would wipe the rendered chips when applying
 * the edited message. {@code reply} is the quote this message renders when it is a reply
 * (null = not a reply) — see {@link ReplyInfo}; a reply's author-mention is synthesized into
 * {@code mentions} (offsets -1). {@code forward} is the origin card when this message is a
 * forward (null = not a forward) — see {@link ForwardInfo}; the message's own author is the
 * forwarder. {@code pinned} is the channel-pin flag (Discord-style); {@code pinnedBy} is the
 * PINNER's username (denormalized at pin time — not the message author) and {@code pinnedAt}
 * the pin moment — both null while unpinned. Pin/unpin pushes ride the existing {@code .edited}
 * topic with the full row. {@code sticker} (16th, appended) is the sticker this message carries
 * — a DENORMALIZED copy stamped at send time; null = no sticker (see {@link StickerRef}).
 * Field order is wire-stable — new fields append at the end only, paired with the client
 * mirror. (The former scalar {@code attachmentFileName}/{@code attachmentUrl} pair was REPLACED
 * by {@code attachments} in a paired client+server pass — dev deploys are always paired.)
 */
public record MessageResponse(
        Long id,
        Long channelId,
        Long authorId,
        String authorUsername,
        String content,
        Instant createdAt,
        List<AttachmentResponse> attachments,
        List<MentionResponse> mentions,
        Instant editedAt,
        List<ReactionSummary> reactions,
        ReplyInfo reply,
        ForwardInfo forward,
        boolean pinned,
        String pinnedBy,
        Instant pinnedAt,
        StickerRef sticker
) {
}
