package oak.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Janus SFU configuration bound from {@code oak.janus.*} (see application-dev.yml). The client uses
 * {@link #restUrl()} for signaling (REST long-poll) and the server hands it to the client together
 * with a short-lived access {@code token} and the room {@code pin} (see {@code JanusVoiceAccessService}
 * — Janus runs with {@code token_auth = true}). The server itself talks to Janus over {@link #adminUrl()}
 * (Admin API: token bookkeeping) when {@link #adminEnabled()} is on; the test profile flips it off,
 * which backs the service with a noop implementation (the {@code MinioProperties} pattern).
 */
@ConfigurationProperties(prefix = "oak.janus")
public record JanusProperties(String restUrl, String wsUrl, String adminSecret, String adminUrl,
                              boolean adminEnabled) {
}
