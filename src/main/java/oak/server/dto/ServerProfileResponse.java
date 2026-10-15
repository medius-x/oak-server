package oak.server.dto;

import oak.server.entity.ServerProfile;
import oak.server.service.AttachmentUrlSigner;

import java.util.List;

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
 * <p>{@code version} (the build's pom version, "dev" when build-info is absent — an IDE run) and
 * {@code capabilities} (the {@link ServerCapabilities} catalogue) are the compatibility skeleton
 * (user decision 2026-10-15): the client SHOWS the version in Diagnostics and gates post-0.1.0
 * features on the flags. An old server sends neither field; the client treats that as the
 * unconditional 0.1.0 base.
 *
 * <p><b>Wire:</b> fields append at the END only; the client mirror changes in the same deploy.
 */
public record ServerProfileResponse(Long id, String name, String avatarKey, String avatarUrl,
                                    String connectionHost, Integer connectionPort,
                                    String connectionScheme,
                                    String version, List<String> capabilities) {

    /** No avatar URL — used where no signer is available. */
    public static ServerProfileResponse from(ServerProfile profile, String version, List<String> capabilities) {
        return new ServerProfileResponse(profile.getId(), profile.getName(),
                profile.getAvatarKey(), null, profile.getConnectionHost(), profile.getConnectionPort(),
                profile.getConnectionScheme(), version, capabilities);
    }

    public static ServerProfileResponse from(ServerProfile profile, AttachmentUrlSigner signer,
                                             String version, List<String> capabilities) {
        return new ServerProfileResponse(profile.getId(), profile.getName(),
                profile.getAvatarKey(), avatarUrl(profile.getAvatarKey(), signer),
                profile.getConnectionHost(), profile.getConnectionPort(), profile.getConnectionScheme(),
                version, capabilities);
    }

    private static String avatarUrl(String avatarKey, AttachmentUrlSigner signer) {
        if (signer == null || avatarKey == null || avatarKey.isBlank()) {
            return null;
        }
        return signer.presignedGetUrl(avatarKey);
    }
}
