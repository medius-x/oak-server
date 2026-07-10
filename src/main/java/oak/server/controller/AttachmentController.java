package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AttachmentUploadRequest;
import oak.server.dto.AttachmentUploadResponse;
import oak.server.service.AttachmentUrlSigner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mints a MinIO upload slot (object key + short-lived presigned PUT URL) for an authenticated
 * client. The client uploads the bytes directly to MinIO, then references the key in a message.
 * JWT-protected like the rest of the API (only {@code /api/auth/**} is public).
 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentUrlSigner attachmentUrlSigner;

    @PostMapping
    public AttachmentUploadResponse requestUpload(@RequestBody @Valid AttachmentUploadRequest request) {
        String key = attachmentUrlSigner.newKey(request.fileName());
        return new AttachmentUploadResponse(key, attachmentUrlSigner.presignedPutUrl(key));
    }
}
