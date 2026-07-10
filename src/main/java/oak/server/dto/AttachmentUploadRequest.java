package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Asks the server to mint an object key and a presigned PUT URL for a file named {@code fileName}.
 * Only authenticated users reach this (the endpoint is JWT-protected).
 */
public record AttachmentUploadRequest(@NotBlank @Size(max = 255) String fileName) {
}
