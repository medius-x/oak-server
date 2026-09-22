package oak.server.dto;

import java.time.Instant;

/**
 * Pushed on the PARENT channel's {@code /topic/channel.{parent}.discussions} topic (a new
 * suffix — it IS registered in the ChannelSubscriptionInterceptor regex). One event shape for
 * the three moments:
 * <ul>
 *   <li>{@code CREATED} — a discussion was started on a message of this channel. Carries the
 *       FULL row (snapshot + zeroed counts): clients that never fetched the channel's list
 *       (a peer sitting in the channel) render the tile straight from the push.</li>
 *   <li>{@code ACTIVITY} — a message was posted into the discussion. Carries the fresh row
 *       (replyCount / lastMessageAt), {@code actorUsername}, and the full posted MESSAGE:
 *       clients that never subscribed to the thread's own topic run MENTION detection over
 *       it (a mention in a thread subscribes and notifies the mentioned user), and the
 *       ORIGIN AUTHOR's client turns the FIRST reply ({@code replyCount == 1}) into a
 *       mention-style ping.</li>
 *   <li>{@code REMOVED} — the discussion was closed (tiles die, an open view kicks back;
 *       {@code discussion} is null — the row is gone). This is the ONLY close signal: a
 *       bare-Long on {@code channel.{discussionId}.deleted} would be ambiguous (message ids
 *       and channel ids share the numeric space).</li>
 * </ul>
 * The row's {@code tracked} flag is broadcast as FALSE for everyone — tracking is
 * viewer-scoped (a ChannelParticipant row), so each client merges its own local state over
 * the broadcast row.
 */
public record DiscussionEvent(
        String type,
        Long discussionId,
        Long parentChannelId,
        Long originMessageId,
        Instant lastMessageAt,
        long replyCount,
        String actorUsername,
        DiscussionResponse discussion,
        MessageResponse message
) {

    public static final String TYPE_CREATED = "CREATED";
    public static final String TYPE_ACTIVITY = "ACTIVITY";
    public static final String TYPE_REMOVED = "REMOVED";
}
