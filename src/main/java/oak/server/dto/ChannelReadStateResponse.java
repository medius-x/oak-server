package oak.server.dto;

/**
 * Per-channel read-state slice for the snapshot endpoint. {@code unreadCount} = messages after the
 * user's {@code lastReadMessageId}; {@code mentionCount} = those that @mention the user (parsed +
 * expanded, capped to the freshest 200 unread — older mentions beyond the window are not counted).
 * {@code lastMessageId} is the channel's newest message id (null when the channel is empty).
 */
public record ChannelReadStateResponse(
        Long channelId,
        long unreadCount,
        long mentionCount,
        Long lastReadMessageId,
        Long lastMessageId
) {
}
