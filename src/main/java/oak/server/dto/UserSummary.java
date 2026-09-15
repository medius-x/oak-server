package oak.server.dto;

import oak.server.entity.User;
import oak.server.service.AttachmentUrlSigner;

/**
 * Compact public view of a user for roster-style listings (e.g. the members panel) and for live
 * {@code /topic/users} updates. Mirrors {@link UserResponse}: {@code avatarKey} is the stable MinIO
 * object key (client disk-cache key); {@code avatarUrl} is a freshly-signed presigned GET URL.
 * {@code displayName} (appended 5th, wire append-only) is the NICKNAME clients render everywhere;
 * {@code username} stays as the stable lookup key only — never shown.
 */
public record UserSummary(Long id, String username, String avatarKey, String avatarUrl, String displayName) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getAvatarKey(), null, user.displayName());
    }

    public static UserSummary from(User user, AttachmentUrlSigner signer) {
        return new UserSummary(user.getId(), user.getUsername(),
                user.getAvatarKey(), avatarUrl(user.getAvatarKey(), signer), user.displayName());
    }

    private static String avatarUrl(String avatarKey, AttachmentUrlSigner signer) {
        if (signer == null || avatarKey == null || avatarKey.isBlank()) {
            return null;
        }
        return signer.presignedGetUrl(avatarKey);
    }
}
