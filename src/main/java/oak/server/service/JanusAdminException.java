package oak.server.service;

/**
 * Thrown when the Janus gateway is unreachable or answers {@code janus:"error"} on a critical step
 * (token minting, room creation). {@code RoomController} maps it to 503 — voice is unavailable, but
 * the rest of the instance keeps working.
 */
public class JanusAdminException extends RuntimeException {

    public JanusAdminException(String message) {
        super(message);
    }
}
