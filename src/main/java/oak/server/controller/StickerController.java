package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AttachmentUploadRequest;
import oak.server.dto.AttachmentUploadResponse;
import oak.server.dto.CreateStickerRequest;
import oak.server.dto.StickerResponse;
import oak.server.entity.Permission;
import oak.server.entity.Sticker;
import oak.server.entity.StickerPack;
import oak.server.entity.User;
import oak.server.repository.StickerPackRepository;
import oak.server.repository.StickerRepository;
import oak.server.service.AttachmentUrlSigner;
import oak.server.service.AuthorizationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * The sticker half of the custom-sticker catalog (phase 2) — the {@code EmojiController}
 * shape over the {@code stickers/} prefix. Upload is two-phase (the avatar discipline):
 * {@code POST /slot} mints a {@code stickers/} key + presigned PUT, the client uploads the
 * bytes, then {@code POST /api/stickers} creates the row inside a pack. A slot whose row
 * never lands is an orphan the night sweep reclaims. Sending a sticker rides the normal
 * message POST ({@code stickerId} on the request) gated only by SEND_MESSAGES — no
 * ATTACH_FILES, exactly like {@code :name:} emoji tokens.
 */
@RestController
@RequestMapping("/api/stickers")
@RequiredArgsConstructor
public class StickerController {

    /** Hard cap on the sticker count (across all packs). */
    static final int MAX_STICKERS = 200;

    private static final String STICKER_KEY_PREFIX = "stickers/";

    private final StickerRepository stickerRepository;
    private final StickerPackRepository packRepository;
    private final AttachmentUrlSigner attachmentUrlSigner;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;

    /** Phase 1 of the upload: mint a {@code stickers/} slot ({@code MANAGE_EMOJIS}). */
    @PostMapping("/slot")
    public AttachmentUploadResponse requestUpload(@AuthenticationPrincipal UserDetails principal,
                                                  @RequestBody @Valid AttachmentUploadRequest request) {
        requireManageStickers(principal);
        requireImageExtension(request.fileName());
        String key = attachmentUrlSigner.newStickerKey(request.fileName());
        return new AttachmentUploadResponse(key, attachmentUrlSigner.presignedPutUrl(key));
    }

    /** Phase 2: create the catalog row inside a pack ({@code MANAGE_EMOJIS}). */
    @PostMapping
    @Transactional
    public StickerResponse create(@AuthenticationPrincipal UserDetails principal,
                                  @RequestBody @Valid CreateStickerRequest request) {
        User actor = requireManageStickers(principal);
        StickerPack pack = packRepository.findById(request.packId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown sticker pack"));
        if (stickerRepository.existsByPackIdAndName(pack.getId(), request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sticker name already taken in this pack");
        }
        if (stickerRepository.count() >= MAX_STICKERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Sticker limit reached (" + MAX_STICKERS + ")");
        }
        if (!request.fileKey().startsWith(STICKER_KEY_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown upload slot");
        }
        Sticker sticker = new Sticker(request.name(), request.fileKey(), pack, actor);
        try {
            sticker = stickerRepository.save(sticker);
        } catch (DataIntegrityViolationException race) {
            // The pre-check lost a concurrent create (per-pack name or file key).
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sticker name already taken in this pack");
        }
        publishPing();
        return StickerResponse.from(sticker, attachmentUrlSigner);
    }

    /** Removes the row and its object; messages carrying the sticker degrade to a
     *  tombstone (no FK exists — the Discord tradeoff). {@code MANAGE_EMOJIS}. */
    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        requireManageStickers(principal);
        Sticker sticker = stickerRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown sticker"));
        String fileKey = sticker.getFileKey();
        stickerRepository.delete(sticker);
        attachmentUrlSigner.delete(fileKey); // best-effort — a dead object is not fatal
        publishPing();
    }

    private User requireManageStickers(UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireServer(actor, Permission.MANAGE_EMOJIS);
        return actor;
    }

    private static void requireImageExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".gif") && !lower.endsWith(".webp")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stickers must be PNG, GIF or WebP images");
        }
    }

    private void publishPing() {
        messagingTemplate.convertAndSend(StickerPackController.STICKER_TOPIC, System.currentTimeMillis());
    }
}
