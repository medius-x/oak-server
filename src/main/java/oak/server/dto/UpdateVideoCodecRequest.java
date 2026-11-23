package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/server/video}: the room video codec to store. Only the
 * three whitelisted values pass ({@code vp8} / {@code vp9} / {@code h264}) —
 * anything else is a 400, so a typo can never reach Janus room creation.
 */
public record UpdateVideoCodecRequest(@NotBlank @Size(max = 16) String codec) {
}
