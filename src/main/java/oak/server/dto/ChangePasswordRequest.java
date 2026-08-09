package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service password change: the current password (verified server-side) and the desired new one.
 * {@code newPassword} carries the same constraints as {@link RegisterRequest#password()} (min 8,
 * max 100) so the rules stay consistent; the new-differs-from-old check is enforced in the service.
 */
public record ChangePasswordRequest(
        @NotBlank String oldPassword,
        @NotBlank @Size(min = 8, max = 100) String newPassword
) {
}
