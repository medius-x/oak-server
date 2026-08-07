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
    GROUP_DM
}
