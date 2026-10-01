package oak.server.dto;

import oak.server.entity.Channel;
import oak.server.entity.ChannelType;

import java.time.Instant;

/**
 * Channel view returned by the server. {@code type} is "TEXT", "VOICE" or "CATEGORY"
 * (the collapsible sidebar section). {@code locked} is PER-VIEWER: true when the viewer
 * may see the channel (VIEW_CHANNEL) but not use it — no READ_MESSAGE_HISTORY (text) /
 * no CONNECT (voice) — which the client renders as the lock badge and a dead click.
 * Channels the viewer may not see at all never reach the wire (filtered out of the
 * list); a CATEGORY is a pure container, so it always travels unlocked. The
 * create-broadcast uses {@link #from(Channel)} (a fresh channel is public).
 * {@code parentId} is the CATEGORY this channel groups under (null = top level) and
 * {@code position} the manual sidebar order — {@code (position, id)} sorts the list.
 * {@code afk} flags THE server's AFK channel (the idle-sweep target — one per server);
 * PUT /api/server/afk pings channels.changed so every viewer refetches the flag.
 * WIRE: adding a component changes the record's arity — clients and servers deploy in lockstep.
 */
public record ChannelResponse(Long id, String name, ChannelType type, Instant createdAt, boolean locked,
                              Long parentId, int position, boolean afk) {

    public static ChannelResponse from(Channel channel) {
        return from(channel, false, false);
    }

    public static ChannelResponse from(Channel channel, boolean locked) {
        return from(channel, locked, false);
    }

    /** The list/rename shape: per-viewer lock + the server-wide AFK flag. */
    public static ChannelResponse from(Channel channel, boolean locked, boolean afk) {
        return new ChannelResponse(channel.getId(), channel.getName(), channel.getType(),
                channel.getCreatedAt(), locked, channel.getParentId(), channel.getPosition(), afk);
    }
}
