package oak.server.dto;

import oak.server.entity.Invite;

import java.time.Instant;

/**
 * One ACTIVE invite link for the settings list. The full secret never rides this DTO —
 * only an 8-char preview (the copyable artifact is the assembled {@code link}); all fields
 * are non-null by construction (the MockMvc lesson: an explicit JSON null reads as
 * "absent" to {@code jsonPath().exists()}).
 */
public record InviteResponse(Long id, String secretPreview, String createdBy, Instant createdAt,
                             Instant expiresAt, String link) {

    private static final int PREVIEW_CHARS = 8;

    public static InviteResponse from(Invite invite, String link) {
        String secret = invite.getSecret();
        String preview = secret.length() <= PREVIEW_CHARS
                ? secret
                : secret.substring(0, PREVIEW_CHARS) + "…";
        return new InviteResponse(invite.getId(), preview, invite.getCreatedBy().displayName(),
                invite.getCreatedAt(), invite.getExpiresAt(), link);
    }
}
