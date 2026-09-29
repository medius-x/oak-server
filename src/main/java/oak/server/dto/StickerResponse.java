package oak.server.dto;

import oak.server.entity.Sticker;
import oak.server.service.AttachmentUrlSigner;

/**
 * One sticker row of {@code GET /api/sticker-packs}. The presigned {@code url} re-signs on
 * every fetch (the attachment-URL discipline); clients cache by the stable {@code key}.
 * {@code uploadedBy} reads the id off the lazy proxy without initializing it (the
 * {@code EmojiResponse} invariant).
 */
public record StickerResponse(Long id, String name, String url, String key, Long uploadedBy) {

    public static StickerResponse from(Sticker sticker, AttachmentUrlSigner signer) {
        return new StickerResponse(sticker.getId(), sticker.getName(),
                signer.presignedGetUrl(sticker.getFileKey()), sticker.getFileKey(),
                sticker.getUploadedBy().getId());
    }
}
