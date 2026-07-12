package oak.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Janus SFU configuration bound from {@code oak.janus.*} (see application-dev.yml).
 * The client uses {@link #restUrl()} for signaling (REST long-poll); the server only
 * hands these values to the client — it doesn't manage Janus sessions itself.
 */
@ConfigurationProperties(prefix = "oak.janus")
public record JanusProperties(String restUrl, String wsUrl, String adminSecret) {
}
