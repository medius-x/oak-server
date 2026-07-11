package oak.server.dto;

/**
 * Login / refresh response: a short-lived bearer access JWT ({@code token}) plus a long-lived
 * opaque {@code refreshToken}, and the access token's lifetime in seconds.
 */
public record AuthResponse(String token, String refreshToken, String tokenType, long expiresIn) {
}
