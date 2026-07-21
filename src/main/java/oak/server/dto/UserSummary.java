package oak.server.dto;

import oak.server.entity.User;
import oak.server.service.AttachmentUrlSigner;

/**
 * Compact public view of a user for roster-style listings (e.g. the members panel) and for live
 * {@code /topic/users} updates. Mirrors {@link UserResponse}: {@code avatarKey} is the stable MinIO
 * object key (client disk-cache key); {@code avatarUrl} is a freshly-signed presigned GET URL.
 */
public record UserSummary(Long id, String username, String avatarKey, String avatarUrl) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getAvatarKey(), null);
    }

    public static UserSummary from(User user, AttachmentUrlSigner signer) {
        return new UserSummary(user.getId(), user.getUsername(),
                user.getAvatarKey(), avatarUrl(user.getAvatarKey(), signer));
    }

    private static String avatarUrl(String avatarKey, AttachmentUrlSigner signer) {
        if (signer == null || avatarKey == null || avatarKey.isBlank()) {
            return null;
        }
        return signer.presignedGetUrl(avatarKey);
    }
}
