package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration body. The two auth fields are the original wire; {@code nickname} (3rd) and
 * {@code inviteSecret} (4th) are appended wire-additive for invite registration. Both new
 * fields are optional: a fresh instance bootstraps its OWNER with neither (the
 * pre-provisioning path), while a non-empty instance REQUIRES a valid secret and honors
 * the optional nickname (blank → the nickname equals the login, the legacy behavior).
 * The nickname grammar mirrors {@link RenameMemberRequest} — every nickname is
 * @mentionable by construction.
 */
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 32) String username,
        @NotBlank @Size(min = 8, max = 100) String password,
        @Pattern(regexp = "[\\p{L}\\p{N}_-]*") @Size(max = 32) String nickname,
        @Size(max = 64) String inviteSecret
) {
}
