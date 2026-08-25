package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AttachmentLinkResponse;
import oak.server.dto.AttachmentUploadRequest;
import oak.server.dto.AttachmentUploadResponse;
import oak.server.service.AttachmentUrlSigner;
import oak.server.service.MessageService;
import org.springframework.http.HttpStatus;
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

    @PostMapping
    public AttachmentUploadResponse requestUpload(@RequestBody @Valid AttachmentUploadRequest request) {
        String key = attachmentUrlSigner.newKey(request.fileName());
        return new AttachmentUploadResponse(key, attachmentUrlSigner.presignedPutUrl(key));
    }

    /** A query param, not a path segment: object keys contain slashes
     *  ({@code attachments/<uuid>/<name>}), which neither {@code @PathVariable} nor encoded
     *  path handling treats kindly. */
    @GetMapping("/link")
    public AttachmentLinkResponse freshLink(String key) {
        if (key == null || key.isBlank() || !messageService.attachmentExists(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown attachment");
        }
        return new AttachmentLinkResponse(attachmentUrlSigner.presignedGetUrl(key));
    }
}
