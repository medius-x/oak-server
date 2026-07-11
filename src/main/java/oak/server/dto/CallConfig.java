package oak.server.dto;

import java.util.List;

/** WebRTC ICE/TURN config handed to clients for peer-connection setup (see {@code GET /api/call/config}). */
public record CallConfig(List<IceServer> iceServers) {

    /** One ICE server: candidate URLs plus optional TURN credentials. */
    public record IceServer(List<String> urls, String username, String credential) {
    }
}
