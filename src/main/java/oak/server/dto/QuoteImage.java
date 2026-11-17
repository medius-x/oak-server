package oak.server.dto;

/**
 * The FIRST image attachment of a quoted (replied-to) message — the quote card's
 * little square preview (user request 2026-11-17). A LIVE snapshot like the rest
 * of {@link ReplyInfo}: assembled at response time from the original's
 * attachments column, so the URL is always freshly presigned (it lives an hour).
 * Never persisted into the frozen forward snapshot ({@code forward_nested_reply}):
 * a URL written once would go stale forever — quotes inside forwards keep the
 * text-only card. Null when the original has no image attachment (a text quote
 * stays a text quote); {@code width}/{@code height} are the sender-measured
 * intrinsic dimensions, nullable forever like in {@link AttachmentRef}.
 */
public record QuoteImage(
        String fileName,
        String key,
        String url,
        Integer width,
        Integer height
) {
}
