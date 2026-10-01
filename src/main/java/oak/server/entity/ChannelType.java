package oak.server.entity;

/**
 * Channel type. Stored as a string column (see {@code @Enumerated(STRING)} on {@link Channel}, length 16).
 * {@code DM} and {@code GROUP_DM} reuse the {@link Channel} + {@code Message} machinery for direct
 * messages: access is governed by {@code ChannelParticipant} membership (the DM branch in
 * {@code AuthorizationService.hasInChannel}), not RBAC, so they are excluded from the public channel
 * list and their {@code /topic/channel.{id}} subscription is gated by {@code DmSubscriptionInterceptor}.
 */
public enum ChannelType {
    TEXT,
    VOICE,
    /** A 1:1 direct message — exactly two participants, deduped by {@link Channel#getDmKey()}. */
    DM,
    /** A group direct message — 3+ participants with an owner; no {@code dmKey}. */
    GROUP_DM,
    /** A discussion (thread) attached to one message of a TEXT channel. Owns its own messages
     *  (they never appear in the parent channel's history), and carries a {@code Discussion}
     *  row freezing the origin-message snapshot (the ForwardInfo discipline — the tile
     *  survives the origin's deletion). Every permission resolves through the PARENT channel
     *  (the delegation branch in {@code AuthorizationService.hasInChannel}); tracking who
     *  follows a discussion = {@code ChannelParticipant} rows on it (posting auto-joins). */
    DISCUSSION,
    /** A collapsible sidebar section (Discord's category). A pure container: it carries no
     *  messages and no participants — TEXT/VOICE channels reference it through
     *  {@code Channel#parentId}. Deletion is refused while children remain. */
    CATEGORY
}
