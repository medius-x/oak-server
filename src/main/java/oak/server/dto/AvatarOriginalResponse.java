package oak.server.dto;

/** Lazy response for {@code GET /api/users/me/avatar/original} — a freshly-signed presigned GET URL. */
public record AvatarOriginalResponse(String url) {
}
