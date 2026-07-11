package oak.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coturn (TURN/STUN) configuration bound from {@code oak.turn.*} (see application-dev.yml).
 * Exposed to clients as ICE servers for WebRTC NAT traversal.
 */
@ConfigurationProperties(prefix = "oak.turn")
public record TurnProperties(String host, int port, String user, String password, String realm) {
}
