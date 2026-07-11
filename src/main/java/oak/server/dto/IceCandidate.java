package oak.server.dto;

/** A single ICE candidate (mirrors {@code dev.onvoid.webrtc.RTCIceCandidate} fields). */
public record IceCandidate(String sdp, String sdpMid, Integer sdpMLineIndex) {
}
