package oak.server.dto;

/**
 * Result of a successful self-rename. The JWT subject is the username, so EVERY previously issued
 * access token of this user stops resolving the moment the name changes — the endpoint therefore
 * returns a FRESH token pair (all refresh tokens are revoked and a new family issued, exactly like
 * a password change) alongside the updated profile, and the client restarts its WebSocket session
 * (the STOMP principal is stamped from the CONNECT-time subject and would otherwise be stale).
 */
public record UserRenamedResponse(UserResponse user, AuthResponse auth) {
}
