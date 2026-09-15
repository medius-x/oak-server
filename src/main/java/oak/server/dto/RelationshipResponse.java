package oak.server.dto;

import oak.server.entity.User;
import oak.server.service.AttachmentUrlSigner;

/**
 * A relationship from the requesting user's perspective, paired with the other user's compact profile.
 * Backs {@code GET /api/users/me/relationships}. The {@link Type} folds the directed entity
 * {@code RelationshipType} into the caller's view: a {@code PENDING} row is outgoing when the caller is
 * the source and incoming when the caller is the target. {@code displayName} (appended 6th, wire
 * append-only) is the NICKNAME the client renders — the username stays a lookup key, never shown.
 */
public record RelationshipResponse(Long userId, String username, Type type, String avatarKey, String avatarUrl,
                                   String displayName) {

    /** Caller-perspective relationship kind (serialized as the enum name). */
    public enum Type {
        FRIEND,
        BLOCKED,
        PENDING_INCOMING,
        PENDING_OUTGOING
    }

    public static RelationshipResponse of(User other, Type type, AttachmentUrlSigner signer) {
        return new RelationshipResponse(other.getId(), other.getUsername(), type,
                other.getAvatarKey(), avatarUrl(other.getAvatarKey(), signer), other.displayName());
    }

    private static String avatarUrl(String avatarKey, AttachmentUrlSigner signer) {
        if (signer == null || avatarKey == null || avatarKey.isBlank()) {
            return null;
        }
        return signer.presignedGetUrl(avatarKey);
    }
}
