package oak.server.dto;

import oak.server.entity.User;

/**
 * Compact public view of a user for roster-style listings (e.g. the members panel).
 * The full {@link UserResponse} stays for a user's own profile ({@code /api/users/me}).
 */
public record UserSummary(Long id, String username) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername());
    }
}
