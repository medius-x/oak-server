package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AttachmentLinkResponse;
import oak.server.dto.AttachmentUploadRequest;
import oak.server.dto.AttachmentUploadResponse;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.EmojiRepository;
import oak.server.repository.StickerRepository;
import oak.server.repository.UserRepository;
import oak.server.service.AttachmentSlotRegistry;
import oak.server.service.AttachmentUrlSigner;
import oak.server.service.AuthorizationService;
import oak.server.service.MessageService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mints MinIO attachment URLs for an authenticated client. The client uploads bytes directly
 * to MinIO over the presigned PUT and then references the key in a message; the fresh-GET
 * endpoint re-signs a key the caller already holds. JWT-protected like the rest of the API
 * (only {@code /api/auth/**} is public).
 *
 * <p>Mint gate (security round 3): the slot requires the global {@code ATTACH_FILES} grant
 * (channel-level overrides are enforced where the channel is known — message create) and a
 * per-user rolling mint quota — previously ANY authenticated account could mint unlimited
 * slots and park unlimited bytes for the orphan-GC grace period. Every minted key is also
 * attributed to its minter in {@link AttachmentSlotRegistry}, which message create and the
 * avatar confirms check (no binding foreign keys).
 *
 * <p>Authorization stance of {@code GET /link}: the object KEY is the capability (same model as
 * the presigned URLs themselves — unguessable {@code prefix/uuid/name} tokens minted by
 * {@code POST /api/attachments}); the endpoint verifies the key belongs to some message and
 * re-signs it, which only shortens the lifetime of what the caller can already fetch. It does
 * NOT re-derive per-channel read rights — the message DTOs hand the same URLs to anyone who can
 * read the channel, and key secrecy is what gates everyone else.
 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentUrlSigner attachmentUrlSigner;
    private final MessageService messageService;
    private final EmojiRepository emojiRepository;
    private final StickerRepository stickerRepository;
    private final AuthorizationService auth;
    private final UserRepository userRepository;
    private final AttachmentSlotRegistry slotRegistry;

    @PostMapping
    public AttachmentUploadResponse requestUpload(@AuthenticationPrincipal UserDetails principal,
                                                  @RequestBody @Valid AttachmentUploadRequest request) {
        User user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        auth.requireServer(user, Permission.ATTACH_FILES);
        if (!slotRegistry.tryAcquireMintQuota(principal.getUsername())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many uploads — try again in a few minutes");
        }
        String key = attachmentUrlSigner.newKey(request.fileName());
        slotRegistry.registerMint(key, principal.getUsername());
        return new AttachmentUploadResponse(key, attachmentUrlSigner.presignedPutUrl(key));
    }

    /** A query param, not a path segment: object keys contain slashes
     *  ({@code attachments/<uuid>/<name>}), which neither {@code @PathVariable} nor encoded
     *  path handling treats kindly. */
    @GetMapping("/link")
    public AttachmentLinkResponse freshLink(String key) {
        // The emoji/sticker OR lives HERE, not in MessageService.attachmentExists: the GC tiers
        // call that method with their own semantics (message-referenced objects), and widening
        // it would silently broaden them; only this endpoint needs the union.
        if (key == null || key.isBlank()
                || (!messageService.attachmentExists(key) && !emojiRepository.existsByFileKey(key)
                        && !stickerRepository.existsByFileKey(key))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown attachment");
        }
        return new AttachmentLinkResponse(attachmentUrlSigner.presignedGetUrl(key));
    }
}
