package oak.server.dto;

import oak.server.entity.User;

import java.time.Instant;

/**
 * Public user view — never exposes the password hash.
 */
public record UserResponse(Long id, String username, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt());
    }
}
