package oak.server.dto;

import oak.server.entity.Sticker;
import oak.server.entity.StickerPack;
import oak.server.service.AttachmentUrlSigner;

import java.util.List;

/**
 * One pack with its stickers nested — {@code GET /api/sticker-packs} returns the whole
 * catalog in one fetch (the client tray and the settings screen both render from it; a
 * single {@code /topic/stickers} ping refetches it after any mutation).
 */
public record StickerPackResponse(Long id, String name, List<StickerResponse> stickers) {

    public static StickerPackResponse from(StickerPack pack, List<Sticker> stickers,
                                           AttachmentUrlSigner signer) {
        return new StickerPackResponse(pack.getId(), pack.getName(),
                stickers.stream().map(sticker -> StickerResponse.from(sticker, signer)).toList());
    }
}
