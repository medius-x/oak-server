package oak.server.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * One attachment of a message, as the client sends it: the MinIO object key obtained from
 * {@code POST /api/attachments} (the client uploaded the bytes there beforehand) plus the
 * display file name. Doubles as the persisted shape inside the {@code messages.attachments}
 * JSON column (see {@code AttachmentListCodec}) — the wire element and the stored row can
 * never drift apart. Server-side validation of a non-blank {@code key} happens in
 * {@code MessageService} (a 400, not a bean-validation error, to match the
 * content-or-attachment rule's style).
 *
 * <p>{@code thumbnailKey}/{@code durationMillis} are the video preview metadata the CLIENT
 * generates at upload time (a frame grab + its timing — the server never runs ffmpeg): the
 * key addresses an ordinary MinIO object holding a small JPEG the client uploaded alongside
 * the video. Both are nullable and optional forever — a video without a preview (old rows,
 * HEVC files the client cannot decode, a failed grab) simply renders as the plain card, and
 * {@code AttachmentListCodec}'s lenient reader keeps pre-preview rows readable.
 *
 * <p>{@code width}/{@code height} are the media's INTRINSIC pixel dimensions, also measured
 * by the client at upload (the same decode that grabs the video preview; an image bitmap
 * decode for images). The server only stores and echoes them: the client reserves the exact
 * layout box via {@code aspect-ratio} BEFORE the bytes load, so attachment-heavy channels
 * never reflow under the viewport. Nullable forever — rows sent before this field keep the
 * estimating layout, and the codec's lenient reader maps absent fields to null.
 *
 * <p>{@code sizeBytes} is the object's byte size, likewise known only to the SENDER
 * ({@code File.size} at upload; the server never terminates the bytes, so it cannot
 * measure). Same trust model as width/height — stored and echoed verbatim. Nullable
 * forever: pre-size rows keep rendering without a size line.
 */
public record AttachmentRef(
        @Size(max = 512) String key,
        @Size(max = 255) String fileName,
        @Size(max = 512) String thumbnailKey,
        @PositiveOrZero Long durationMillis,
        @PositiveOrZero Integer width,
        @PositiveOrZero Integer height,
        @PositiveOrZero Long sizeBytes
) {

    /** The pre-preview shape — kept for tests and for call sites that have no video metadata. */
    public AttachmentRef(String key, String fileName) {
        this(key, fileName, null, null, null, null, null);
    }

    /** The pre-dimension video-preview shape (no measured width/height yet). */
    public AttachmentRef(String key, String fileName, String thumbnailKey, Long durationMillis) {
        this(key, fileName, thumbnailKey, durationMillis, null, null, null);
    }
}
