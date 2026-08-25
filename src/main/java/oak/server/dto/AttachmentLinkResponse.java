package oak.server.dto;

/**
 * Response for {@code GET /api/attachments/link?key=…} — a freshly-signed presigned GET URL for
 * an attachment key the caller already holds. The URLs inside a {@link MessageResponse} are
 * signed per fetch and expire after an hour; a STREAMED (uncached) playback that outlives its
 * link — typically a paused track or video resumed hours later — re-mints through this endpoint
 * instead of reloading the whole channel history.
 */
public record AttachmentLinkResponse(String url) {
}
