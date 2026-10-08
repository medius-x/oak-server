package oak.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Janus SFU configuration bound from {@code oak.janus.*} (see application.yml). The client uses
 * {@link #clientRestUrl()} for signaling (REST long-poll) and the server hands it to the client together
 * with a short-lived access {@code token} and the room {@code pin} (see {@code JanusVoiceAccessService}
 * — Janus runs with {@code token_auth = true}). The server itself talks to Janus over {@link #restUrl()}
 * (room lifecycle) and {@link #adminUrl()} (token bookkeeping) when {@link #adminEnabled()} is on; the
 * test profile flips admin off, which backs the service with a noop implementation (the
 * {@code MinioProperties} pattern).
 *
 * <p>{@code publicRestUrl} splits the two ROUTES to Janus when a TLS entry point sits in front: the
 * server must dial the compose-internal address, while clients receive the public https URL in
 * {@code RoomResponse}. Blank/absent (the dev topology, where both are the same address) makes
 * {@link #clientRestUrl()} fall back to {@link #restUrl()}.
 */
@ConfigurationProperties(prefix = "oak.janus")
public record JanusProperties(String restUrl, String publicRestUrl, String wsUrl, String adminSecret,
                              String adminUrl, boolean adminEnabled) {

    /** The URL handed to CLIENTS: the public route when configured, else the server's own. */
    public String clientRestUrl() {
        return publicRestUrl != null && !publicRestUrl.isBlank() ? publicRestUrl : restUrl;
    }
}
