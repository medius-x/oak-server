package oak.server.dto;

/**
 * Body of {@code POST /api/auth/refresh}: presents the (opaque) refresh token to mint a new
 * access JWT + a rotated refresh token. The endpoint is public — the refresh token is the credential.
 */
public record RefreshRequest(String refreshToken) {
}
