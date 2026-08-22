package oak.server.dto;

import java.time.Instant;

/**
 * The origin card a forwarded message renders above its text (Discord-style): where the original
 * lived ({@code serverName → channelName}), who wrote it and when. The message's own author is
 * the forwarder; the card shows these fields. A snapshot — later edits/deletion of the original do
 * not propagate.
 *
 * <p>{@code trusted} is the anti-spoofing marker: true only when the SERVER derived the origin
 * itself (intra-instance forward); false for a client-supplied cross-instance snapshot, which this
 * instance cannot verify (no federation protocol). Clients render "unverified" on such cards.
 *
 * <p>{@code reply}: a snapshot of the source's quote tree, present when a REPLY was forwarded —
 * the frozen quote the source carried at forward time (the source may be edited or even deleted
 * afterwards; the snapshot stays). Cross-instance forwards receive this tree from the client
 * (same trust model as every other mode-b field). Null when the source was not a reply.
 */
public record ForwardInfo(
        String serverName,
        String channelName,
        Long authorId,
        String authorUsername,
        Instant createdAt,
        boolean trusted,
        ReplyInfo reply
) {
}
