package oak.server.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/users/me/friends} — add a friend by username. */
public record AddFriendRequest(@NotBlank String username) {
}
