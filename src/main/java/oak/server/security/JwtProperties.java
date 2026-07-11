package oak.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT/token configuration bound from {@code oak.security.jwt.*} (see application-dev.yml).
 * The access token is the short-lived signed JWT; the refresh token is a long-lived opaque token.
 */
@ConfigurationProperties(prefix = "oak.security.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenExpiration,
        Duration refreshTokenExpiration
) {
}
