package oak.server.dto;

import oak.server.entity.Channel;
import oak.server.entity.ChannelType;

import java.time.Instant;

public record ChannelResponse(Long id, String name, ChannelType type, Instant createdAt) {

    public static ChannelResponse from(Channel channel) {
        return new ChannelResponse(channel.getId(), channel.getName(), channel.getType(), channel.getCreatedAt());
    }
}
