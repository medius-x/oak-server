package oak.server.dto;

/**
 * The signed-in member's resolved (override-aware) permissions on a single channel — returned by
 * {@code GET /api/channels/{id}/permissions/me} so the client can hide the message composer (input +
 * attachment + send) when the member can't write here, and the voice share/camera buttons when they
 * can't stream (security round 6 — the STREAM gate is client-side: the gateway has no video mute).
 * Resolved through {@link oak.server.service.AuthorizationService#hasInChannel} with
 * {@link oak.server.entity.Permission#SEND_MESSAGES}, {@link oak.server.entity.Permission#ATTACH_FILES}
 * and {@link oak.server.entity.Permission#STREAM}; ADMINISTRATOR / OWNER bypass overrides as usual.
 * WIRE: fields are append-only; both mirrors change in one pass (paired deploy).
 */
public record ChannelMyPermissions(boolean sendMessages, boolean attachFiles, boolean stream) {
}
