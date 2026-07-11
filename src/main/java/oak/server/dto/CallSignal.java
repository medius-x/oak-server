package oak.server.dto;

/**
 * WebRTC call-signaling envelope, relayed server-side from one user to another
 * ({@code /app/call.signal} → the target's {@code /user/queue/call.signal}). The server stamps
 * {@code from} from the authenticated principal; clients fill {@code to}, {@code type} and the
 * relevant payload — {@code sdp} for OFFER/ANSWER, {@code candidate} for ICE.
 *
 * <p>{@code type}: {@code OFFER}, {@code ANSWER}, {@code ICE}, {@code RING}, {@code ACCEPT},
 * {@code REJECT}, {@code HANGUP}.
 */
public record CallSignal(String type, String from, String to, String sdp, IceCandidate candidate) {
}
