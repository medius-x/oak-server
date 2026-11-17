package oak.server.dto;

/**
 * The search result's context for a hit that lives in a DISCUSSION thread
 * (2026-11-18): {@code id} is the DISCUSSION channel's own id — it matches the
 * hit's {@code MessageResponse.channelId}, so the client resolves the pair in
 * one map lookup. {@code parentChannelId} names the owning channel (the
 * client's channel list has no discussions — without this the hit would carry
 * no place to jump to, the very reason threads stayed unsearchable); the
 * origin fields are the frozen snapshot ({@code Discussion}) so the client can
 * open the thread view without an extra fetch. Only present for the CURRENT
 * page's discussion hits; absent (empty) when none or on a pre-thread-search
 * client contract.
 */
public record DiscussionHit(
        Long id,
        Long parentChannelId,
        Long originMessageId,
        String originSnippet
) {
}
