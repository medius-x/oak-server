package oak.server.dto;

import oak.server.entity.ServerProfile;
import oak.server.service.AttachmentUrlSigner;

/**
 * Public view of the instance identity. {@code avatarKey} is the MinIO object key (the client uses it
 * as a stable disk-cache key, since the presigned URL below changes on every fetch); {@code avatarUrl}
 * is a freshly-signed presigned GET URL when an icon is set, otherwise null — built by
 * {@link #from(ServerProfile, AttachmentUrlSigner)} so it is re-signed per request. Mirrors
 * {@link UserResponse}. {@code connectionHost}/{@code connectionPort}/{@code connectionScheme} are the advertised
 * client-connection address (what a member types into "Add server") — storage for the future
 * remote-provisioning flow; null parts mean "not set" (a null scheme is the historical default
 * http).
 *
 * <p><b>Wire:</b> fields append at the END only; the client mirror changes in the same deploy.
 */
public record ServerProfileResponse(Long id, String name, String avatarKey, String avatarUrl,
                                    String connectionHost, Integer connectionPort,
                                    String connectionScheme) {

    /** No avatar URL — used where no signer is available. */
    public static ServerProfileResponse from(ServerProfile profile) {
        return new ServerProfileResponse(profile.getId(), profile.getName(),
                profile.getAvatarKey(), null, profile.getConnectionHost(), profile.getConnectionPort(),
                profile.getConnectionScheme());
    }

    public static ServerProfileResponse from(ServerProfile profile, AttachmentUrlSigner signer) {
        return new ServerProfileResponse(profile.getId(), profile.getName(),
                profile.getAvatarKey(), avatarUrl(profile.getAvatarKey(), signer),
                profile.getConnectionHost(), profile.getConnectionPort(), profile.getConnectionScheme());
    }

    private static String avatarUrl(String avatarKey, AttachmentUrlSigner signer) {
        if (signer == null || avatarKey == null || avatarKey.isBlank()) {
            return null;
        }
        return signer.presignedGetUrl(avatarKey);
    }
}
