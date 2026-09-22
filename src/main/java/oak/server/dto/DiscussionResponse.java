package oak.server.dto;

import oak.server.entity.Discussion;

import java.time.Instant;

/**
 * The discussion wire view (a NEW dto — not part of the append-only MessageResponse contract,
 * so it carries no paired-deploy obligations). {@code id} is the DISCUSSION channel's id (the
 * value clients address messages/subscriptions by); the {@code origin*} block is the FROZEN
 * start-message snapshot from the {@code discussions} row. {@code replyCount} is computed live
 * by the service (the thread's message count). {@code tracked} is VIEWER-SCOPED — whether this
 * particular caller follows the discussion (a {@code ChannelParticipant} row): the same
 * response object is deliberately not cacheable across viewers.
 */
public record DiscussionResponse(
        Long id,
        Long parentChannelId,
        Long originMessageId,
        String originSnippet,
        Long originAuthorId,
        String originAuthorName,
        Instant originCreatedAt,
        Instant createdAt,
        Instant lastMessageAt,
        long replyCount,
        boolean tracked
) {

    public static DiscussionResponse from(Discussion discussion, long replyCount, boolean tracked) {
        return new DiscussionResponse(
                discussion.getChannelId(),
                discussion.getParentChannelId(),
                discussion.getOriginMessageId(),
                discussion.getOriginSnippet(),
                discussion.getOriginAuthorId(),
                discussion.getOriginAuthorName(),
                discussion.getOriginCreatedAt(),
                discussion.getCreatedAt(),
                discussion.getLastMessageAt(),
                replyCount,
                tracked);
    }
}
