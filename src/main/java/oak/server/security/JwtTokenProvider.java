package oak.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates HS256 JWT access tokens. The token subject is the username.
 */
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final Duration accessTokenExpiration;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = properties.accessTokenExpiration();
    }

    /** Builds a signed access token for the given username (subject). */
    public String generateToken(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiration)))
                .signWith(key)
                .compact();
    }

    /** True iff the signature is valid and the token has not expired. */
    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Extracts the username (subject). Only call after {@link #isValid(String)}. */
    public String extractUsername(String token) {
        return parse(token).getSubject();
    }

    public long accessTokenExpirationSeconds() {
        return accessTokenExpiration.toSeconds();
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
