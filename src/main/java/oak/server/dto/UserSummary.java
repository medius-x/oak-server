package oak.server.dto;

import oak.server.entity.User;
import oak.server.service.AttachmentUrlSigner;

/**
 * Compact public view of a user for roster-style listings (e.g. the members panel). The full
 * {@link UserResponse} stays for a user's own profile ({@code /api/users/me}). {@code avatarUrl} is
 * a freshly-signed presigned GET URL when the user has an avatar; see {@link UserResponse}.
 */
public record UserSummary(Long id, String username, String avatarUrl) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername(), null);
    }

    public static UserSummary from(User user, AttachmentUrlSigner signer) {
        return new UserSummary(user.getId(), user.getUsername(), avatarUrl(user, signer));
    }

    private static String avatarUrl(User user, AttachmentUrlSigner signer) {
        if (signer == null) {
            return null;
        }
        String key = user.getAvatarKey();
        return (key == null || key.isBlank()) ? null : signer.presignedGetUrl(key);
    }
}
