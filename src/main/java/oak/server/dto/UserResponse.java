package oak.server.dto;

import oak.server.entity.User;
import oak.server.service.AttachmentUrlSigner;

import java.time.Instant;

/**
 * Public user view — never exposes the password hash. {@code avatarUrl} is a freshly-signed
 * presigned GET URL when the user has an avatar, otherwise null; built by
 * {@link #from(User, AttachmentUrlSigner)} so the URL is re-signed per request and never ages out.
 */
public record UserResponse(Long id, String username, Instant createdAt, String avatarUrl) {

    /** No avatar URL — used where no signer is available (e.g. right after register, no avatar yet). */
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt(), null);
    }

    public static UserResponse from(User user, AttachmentUrlSigner signer) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt(),
                avatarUrl(user, signer));
    }

    private static String avatarUrl(User user, AttachmentUrlSigner signer) {
        if (signer == null) {
            return null;
        }
        String key = user.getAvatarKey();
        return (key == null || key.isBlank()) ? null : signer.presignedGetUrl(key);
    }
}
