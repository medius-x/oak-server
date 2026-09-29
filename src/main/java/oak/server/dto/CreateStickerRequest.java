package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Completes the two-phase sticker upload: the {@code fileKey} was minted by
 * {@code POST /api/stickers/slot} and its bytes PUT by the client; this creates the catalog
 * row inside the given pack. The name charset is deliberately ASCII ({@code [a-z0-9_-]}) —
 * the client upload form reuses the emoji name sanitizer.
 */
public record CreateStickerRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "[a-z0-9_-]{1,32}") String name,
        @NotNull Long packId,
        @NotBlank @Size(max = 512) String fileKey) {
}
