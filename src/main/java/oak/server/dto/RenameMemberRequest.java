package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Nickname set body ({@code PATCH /api/members/{userId}/nickname}). The grammar mirrors the
 * {@code MentionParser} token class — Unicode letters/digits, underscore, dash, no spaces — so
 * every nickname is @mentionable by construction.
 */
public record RenameMemberRequest(
        @NotBlank @Size(min = 1, max = 32) @Pattern(regexp = "[\\p{L}\\p{N}_-]+") String nickname) {
}
