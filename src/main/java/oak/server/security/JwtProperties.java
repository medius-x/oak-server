package oak.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT configuration bound from {@code oak.security.jwt.*} (see application-dev.yml).
 */
@ConfigurationProperties(prefix = "oak.security.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenExpiration
) {
}
