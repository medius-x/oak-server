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
 */
public record AttachmentRef(
        @Size(max = 512) String key,
        @Size(max = 255) String fileName,
        @Size(max = 512) String thumbnailKey,
        @PositiveOrZero Long durationMillis
) {

    /** The pre-preview shape — kept for tests and for call sites that have no video metadata. */
    public AttachmentRef(String key, String fileName) {
        this(key, fileName, null, null);
    }
}
