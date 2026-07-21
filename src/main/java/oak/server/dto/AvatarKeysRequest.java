package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Binds freshly-uploaded avatar objects to the current user — the confirm step of the two-phase
 * avatar upload. {@code avatarKey} is the cropped 256px image; {@code originalKey} is the pre-crop
 * original (nullable — null on a re-crop means "keep the existing original, only the crop changed").
 */
public record AvatarKeysRequest(
        @NotBlank @Size(max = 512) String avatarKey,
        @Size(max = 512) String originalKey) {
}
