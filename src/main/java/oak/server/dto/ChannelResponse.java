package oak.server.dto;

import oak.server.entity.Channel;
import oak.server.entity.ChannelType;

import java.time.Instant;

/**
 * Channel view returned by the server. {@code type} is "TEXT" or "VOICE". {@code locked} is
 * PER-VIEWER: true when the viewer may see the channel (VIEW_CHANNEL) but not use it — no
 * READ_MESSAGE_HISTORY (text) / no CONNECT (voice) — which the client renders as the lock badge
 * and a dead click. Channels the viewer may not see at all never reach the wire (filtered out of
 * the list). The create-broadcast uses {@link #from(Channel)} (a fresh channel is public).
 * WIRE: adding a component changes the record's arity — clients and servers deploy in lockstep.
 */
public record ChannelResponse(Long id, String name, ChannelType type, Instant createdAt, boolean locked) {

    public static ChannelResponse from(Channel channel) {
        return new ChannelResponse(channel.getId(), channel.getName(), channel.getType(),
                channel.getCreatedAt(), false);
    }

    public static ChannelResponse from(Channel channel, boolean locked) {
        return new ChannelResponse(channel.getId(), channel.getName(), channel.getType(),
                channel.getCreatedAt(), locked);
    }
}
