package oak.server.service.igdb;

/**
 * An upstream IGDB/Twitch failure (token endpoint error, transport timeout, 5xx).
 * Carries no secrets — the message is the client-facing reason only.
 */
public class IgdbException extends RuntimeException {

    public IgdbException(String message) {
        super(message);
    }

    public IgdbException(String message, Throwable cause) {
        super(message, cause);
    }
}
