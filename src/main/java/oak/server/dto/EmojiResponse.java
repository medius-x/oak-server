package oak.server.dto;

import oak.server.entity.CustomEmoji;
import oak.server.service.AttachmentUrlSigner;

/**
 * One catalog row of {@code GET /api/emojis}. The presigned {@code url} re-signs on every
 * fetch (the {@code attachment}-URL discipline); clients cache by the stable {@code key}.
 * {@code uploadedBy} reads the id off the lazy proxy without initializing it (the
 * {@code toResponse} invariant).
 */
public record EmojiResponse(Long id, String name, String url, String key, Long uploadedBy) {

    public static EmojiResponse from(CustomEmoji emoji, AttachmentUrlSigner signer) {
        return new EmojiResponse(emoji.getId(), emoji.getName(),
                signer.presignedGetUrl(emoji.getFileKey()), emoji.getFileKey(),
                emoji.getUploadedBy().getId());
    }
}
