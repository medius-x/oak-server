package oak.server.dto;

import oak.server.entity.ServerProfile;
import oak.server.service.AttachmentUrlSigner;

/**
 * Public view of the instance identity. {@code avatarKey} is the MinIO object key (the client uses it
 * as a stable disk-cache key, since the presigned URL below changes on every fetch); {@code avatarUrl}
 * is a freshly-signed presigned GET URL when an icon is set, otherwise null — built by
 * {@link #from(ServerProfile, AttachmentUrlSigner)} so it is re-signed per request. Mirrors
 * {@link UserResponse}.
 */
public record ServerProfileResponse(Long id, String name, String avatarKey, String avatarUrl) {

    /** No avatar URL — used where no signer is available. */
    public static ServerProfileResponse from(ServerProfile profile) {
        return new ServerProfileResponse(profile.getId(), profile.getName(),
                profile.getAvatarKey(), null);
    }

    public static ServerProfileResponse from(ServerProfile profile, AttachmentUrlSigner signer) {
        return new ServerProfileResponse(profile.getId(), profile.getName(),
                profile.getAvatarKey(), avatarUrl(profile.getAvatarKey(), signer));
    }

    private static String avatarUrl(String avatarKey, AttachmentUrlSigner signer) {
        if (signer == null || avatarKey == null || avatarKey.isBlank()) {
            return null;
        }
        return signer.presignedGetUrl(avatarKey);
    }
}
