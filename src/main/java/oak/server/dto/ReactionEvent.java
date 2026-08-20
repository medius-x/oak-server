package oak.server.dto;

import java.util.List;

/**
 * Pushed on {@code /topic/channel.{id}.reactions} after any reaction add/remove: the full,
 * deterministic snapshot of the message's reactions (see {@link ReactionSummary}). The client
 * replaces the rendered chip row wholesale — no delta merging needed.
 */
public record ReactionEvent(
        Long messageId,
        List<ReactionSummary> reactions
) {
}
