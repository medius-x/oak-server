package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/users/me/username} — the self-rename. Same username rules as
 * registration ({@code RegisterRequest}); uniqueness is checked in the service (409 on a taken
 * name). Gated by {@code CHANGE_NICKNAME} server-side.
 */
public record RenameUsernameRequest(@NotBlank @Size(min = 3, max = 32) String username) {
}
