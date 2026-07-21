package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Binds a freshly-uploaded avatar MinIO object to the current user — the confirm step of the
 * two-phase avatar upload. {@code key} is the object key returned by the upload-slot endpoint
 * ({@code POST /api/users/me/avatar}).
 */
public record AvatarKeyRequest(@NotBlank @Size(max = 512) String key) {
}
