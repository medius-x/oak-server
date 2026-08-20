package oak.server.dto;

import java.util.List;

/**
 * One emoji's aggregated state on a message: the whitelisted slug (never the glyph — the client
 * maps slugs to glyphs), how many users reacted, and the reacting users as parallel id/username
 * lists ({@code userIds[i]} ↔ {@code usernames[i]}) in insertion order. The client derives "my
 * own reaction" by finding itself in {@code userIds} (the payload is a broadcast, so "me" cannot
 * be resolved server-side) and shows {@code usernames} in the chip's "who reacted" popup.
 *
 * <p>Snapshot order is deterministic (summaries sorted by slug, users in insertion order) so the
 * REST response and the WS echo of the same change render identically on the client.
 */
public record ReactionSummary(
        String reaction,
        int count,
        List<Long> userIds,
        List<String> usernames
) {
}
