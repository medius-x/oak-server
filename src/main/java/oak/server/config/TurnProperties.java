package oak.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Coturn (TURN/STUN) configuration bound from {@code oak.turn.*} (see application-dev.yml).
 * Exposed to clients as ICE servers for WebRTC NAT traversal.
 *
 * <p>{@code staticAuthSecret} backs coturn's {@code --use-auth-secret} mode: oak-server
 * issues short-lived REST-API credentials (username {@code "<expiry-epoch>:<login>"},
 * password {@code base64(HMAC-SHA1(secret, username))}) per {@code GET /api/call/config}
 * call — replacing the old single static user/password pair that every account (and the
 * public repo defaults) shared forever, which made the relay an open tunnel.
 * {@code credentialTtl} is how long a freshly issued credential stays valid; allocations
 * outlive their credential, so a call started before the expiry keeps its relay.
 */
@ConfigurationProperties(prefix = "oak.turn")
public record TurnProperties(
        String host,
        int port,
        String staticAuthSecret,
        Duration credentialTtl,
        String realm
) {
}
