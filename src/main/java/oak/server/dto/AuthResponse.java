package oak.server.dto;

/**
 * Login response: a bearer JWT plus its lifetime in seconds.
 */
public record AuthResponse(String token, String tokenType, long expiresIn) {
}
