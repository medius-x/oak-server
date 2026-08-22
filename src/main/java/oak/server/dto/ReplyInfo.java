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
 *
 * <p>{@code forward}: the original's forward origin when the replied-to message is itself a
 * forward (else null). The quote's author is the FORWARDER (the message's author), and without
 * this snapshot the client's quote would read as if the user were replying to the ORIGINAL's
 * author — the forwarded-ness must survive into the quote.
 *
 * <p>{@code reply}: the original's OWN quote when the original is itself a reply (else null) —
 * quotes nest into cards recursively (a reply to a reply to a …). LIVE data like the rest of the
 * record: edits/deletions of any ancestor surface on every re-fetch. {@code null} here means
 * TRUNCATION (the nesting hit {@code MessageService.MAX_QUOTE_DEPTH}); a dangling reference is
 * {@code deleted=true} instead — clients must not conflate the two.
 */
public record ReplyInfo(
        Long messageId,
        Long authorId,
        String authorUsername,
        String preview,
        Instant createdAt,
        boolean deleted,
        ForwardInfo forward,
        ReplyInfo reply
) {
}
