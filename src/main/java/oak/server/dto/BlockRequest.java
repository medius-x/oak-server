package oak.server.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/users/me/blocks} — block a user by username. */
public record BlockRequest(@NotBlank String username) {
}
