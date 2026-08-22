package oak.server.dto;

import java.time.Instant;

/**
 * The quote a reply renders above its text (Discord-style): who was replied to and a one-line
 * preview of the original. Built server-side at response time from the <b>live</b> original, so
 * edits to the original are reflected in quotes on every re-fetch. {@code deleted} is the marker
 * for a dangling {@code repliedToId} (the original was removed — no FK on the reference, see
 * {@code Message#getRepliedToId()}); then every other field is null and the client greys the
 * quote out instead of dropping it.
 *
 * <p>{@code preview}: the original's content flattened to one line and capped (100 chars +
 * "…"); an attachment-only original falls back to its file name.
 */
public record ReplyInfo(
        Long messageId,
        Long authorId,
        String authorUsername,
        String preview,
        Instant createdAt,
        boolean deleted
) {
}
