package oak.server.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the JWT layer — no Spring context, no DB, so
 * {@code mvn package} stays green offline.
 */
class JwtTokenProviderTest {

    // >= 256 bits for HS256
    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret-0123456789";

    private final JwtTokenProvider provider =
            new JwtTokenProvider(new JwtProperties(SECRET, Duration.ofHours(1), Duration.ofDays(30)));

    @Test
    void generatedTokenIsValidAndCarriesUsername() {
        String token = provider.generateToken("alice");

        assertTrue(provider.isValid(token));
        assertEquals("alice", provider.extractUsername(token));
    }

    @Test
    void tamperedTokenIsInvalid() {
        String token = provider.generateToken("alice");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertFalse(provider.isValid(tampered));
    }

    @Test
    void garbageStringIsInvalid() {
        assertFalse(provider.isValid("not-a-jwt"));
    }
}
