package oak.server.dto;

import oak.server.entity.Role;
import oak.server.entity.User;
import oak.server.service.AttachmentUrlSigner;

import java.util.List;

/**
 * A member with their roles — backs {@code GET /api/members} (the management roster, distinct from the
 * presence roster {@code GET /api/users}). {@code owner} flags the single OWNER-holder; {@code banned}
 * flags a banned account (so the client can mark it and offer Unban); {@code removed} flags a
 * permanently removed account (true only on {@code GET /api/members/bans} rows — removed members are
 * absent from the live list; removal has no undo, so the client renders it without actions).
 * {@code nickname} is the display name (appended 7th — wire append-only, paired client deploy).
 * {@code dmRestricted} (appended 9th) flags a member holding a role with
 * {@code RESTRICT_PRIVATE_MESSAGES}: the client dims DM entry points toward them.
 */
public record MemberResponse(Long userId, String username, String avatarUrl, List<Long> roleIds,
                             boolean owner, boolean banned, String nickname, boolean removed,
                             boolean dmRestricted) {

    public static MemberResponse from(User user, AttachmentUrlSigner signer, boolean owner,
                                      boolean dmRestricted) {
        List<Long> roleIds = user.getRoles() == null
                ? List.of()
                : user.getRoles().stream().map(Role::getId).sorted().toList();
        return new MemberResponse(user.getId(), user.getUsername(), avatarUrl(user, signer), roleIds,
                owner, user.isBanned(), user.getNickname(), user.isRemoved(), dmRestricted);
    }

    private static String avatarUrl(User user, AttachmentUrlSigner signer) {
        if (signer == null || user.getAvatarKey() == null || user.getAvatarKey().isBlank()) {
            return null;
        }
        return signer.presignedGetUrl(user.getAvatarKey());
    }
}
