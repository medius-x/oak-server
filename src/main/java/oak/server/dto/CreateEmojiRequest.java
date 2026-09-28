package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Completes the two-phase emoji upload: the {@code fileKey} was minted by
 * {@code POST /api/emojis/slot} and its bytes PUT by the client; this creates the catalog
 * row. The name charset is deliberately ASCII ({@code [a-z0-9_-]}) — the client-side
 * {@code :name:} parser uses the same charset, keeping token matching trivial.
 */
public record CreateEmojiRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "[a-z0-9_-]{1,32}") String name,
        @NotBlank @Size(max = 512) String fileKey) {
}
