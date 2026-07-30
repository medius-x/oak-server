package oak.server.dto;

import oak.server.entity.Role;
import oak.server.entity.User;
import oak.server.service.AttachmentUrlSigner;

import java.util.List;

/**
 * A member with their roles — backs {@code GET /api/members} (the management roster, distinct from the
 * presence roster {@code GET /api/users}). {@code owner} flags the single OWNER-holder; {@code banned}
 * flags a banned account (so the client can mark it and offer Unban — kick is transient and not modelled).
 */
public record MemberResponse(Long userId, String username, String avatarUrl, List<Long> roleIds,
                             boolean owner, boolean banned) {

    public static MemberResponse from(User user, AttachmentUrlSigner signer, boolean owner) {
        List<Long> roleIds = user.getRoles() == null
                ? List.of()
                : user.getRoles().stream().map(Role::getId).sorted().toList();
        return new MemberResponse(user.getId(), user.getUsername(), avatarUrl(user, signer), roleIds,
                owner, user.isBanned());
    }

    private static String avatarUrl(User user, AttachmentUrlSigner signer) {
        if (signer == null || user.getAvatarKey() == null || user.getAvatarKey().isBlank()) {
            return null;
        }
        return signer.presignedGetUrl(user.getAvatarKey());
    }
}
