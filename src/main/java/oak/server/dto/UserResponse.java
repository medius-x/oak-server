package oak.server.dto;

import oak.server.entity.User;
import oak.server.service.AttachmentUrlSigner;

import java.time.Instant;

/**
 * Public user view — never exposes the password hash. {@code avatarKey} is the MinIO object key (the
 * client uses it as a stable disk-cache key, since the presigned URL below changes on every fetch);
 * {@code avatarUrl} is a freshly-signed presigned GET URL when the user has an avatar, otherwise
 * null — built by {@link #from(User, AttachmentUrlSigner)} so it is re-signed per request.
 * {@code displayName} (appended 6th, wire append-only) is the nickname the client renders.
 */
public record UserResponse(Long id, String username, Instant createdAt, String avatarKey, String avatarUrl,
                           String displayName) {

    /** No avatar — used where no signer is available (e.g. right after register, no avatar yet). */
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt(),
                user.getAvatarKey(), null, user.displayName());
    }

    public static UserResponse from(User user, AttachmentUrlSigner signer) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt(),
                user.getAvatarKey(), avatarUrl(user.getAvatarKey(), signer), user.displayName());
    }

    private static String avatarUrl(String avatarKey, AttachmentUrlSigner signer) {
        if (signer == null || avatarKey == null || avatarKey.isBlank()) {
            return null;
        }
        return signer.presignedGetUrl(avatarKey);
    }
}
