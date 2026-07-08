package oak.server.dto;

import oak.server.entity.Message;

import java.time.Instant;

/** Flattened message view — author resolved to id + username, no lazy graph leaked. */
public record MessageResponse(
        Long id,
        Long channelId,
        Long authorId,
        String authorUsername,
        String content,
        Instant createdAt
) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getChannel().getId(),
                message.getAuthor().getId(),
                message.getAuthor().getUsername(),
                message.getContent(),
                message.getCreatedAt());
    }
}
