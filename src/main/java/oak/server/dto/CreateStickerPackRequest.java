package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creates a sticker pack. Unlike sticker names (ASCII, they double as upload-form slugs),
 * the pack name is free display text — trimmed server-side, unique across the instance.
 */
public record CreateStickerPackRequest(
        @NotBlank @Size(max = 32) String name) {
}
