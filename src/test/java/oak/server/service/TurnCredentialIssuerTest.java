package oak.server.service;

import oak.server.config.TurnProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnCredentialIssuerTest {

    private static final TurnProperties PROPERTIES = new TurnProperties(
            "localhost", 3478, "test-secret", Duration.ofHours(1), "oak.local");

    private final TurnCredentialIssuer issuer = new TurnCredentialIssuer(PROPERTIES);

    @Test
    void hmacSha1MatchesTheRfc2202Vector() {
        // RFC 2202 test case 2: key "Jefe", data "what do ya want for nothing?".
        byte[] mac = TurnCredentialIssuer.hmacSha1("Jefe", "what do ya want for nothing?");
        assertEquals("effcdf6ae5eb2fa2d27416d5f184df9c259a7c79", HexFormat.of().formatHex(mac));
    }

    @Test
    void usernameCarriesFutureExpiryAndPrincipal() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        TurnCredentialIssuer.TurnCredential credential = issuer.issue("alice", now);
        String[] usernameParts = credential.username().split(":", 2);
        assertEquals(2, usernameParts.length);
        assertEquals("alice", usernameParts[1]);
        assertEquals(now.getEpochSecond() + 3600, Long.parseLong(usernameParts[0]));
    }

    @Test
    void credentialIsBase64OfTheDocumentedHmac() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        TurnCredentialIssuer.TurnCredential credential = issuer.issue("alice", now);
        String expected = Base64.getEncoder().encodeToString(
                TurnCredentialIssuer.hmacSha1("test-secret", credential.username()));
        assertEquals(expected, credential.credential());
        // A decode must round-trip to 20 bytes (SHA-1) — the shape coturn expects.
        assertEquals(20, Base64.getDecoder().decode(credential.credential()).length);
    }

    @Test
    void issuingIsDeterministicForTheSameInstantAndPrincipal() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        assertEquals(issuer.issue("alice", now), issuer.issue("alice", now));
    }

    @Test
    void laterIssueOrDifferentPrincipalChangesBothFields() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        TurnCredentialIssuer.TurnCredential first = issuer.issue("alice", now);
        TurnCredentialIssuer.TurnCredential later = issuer.issue("alice", now.plusSeconds(30));
        TurnCredentialIssuer.TurnCredential bob = issuer.issue("bob", now);
        assertNotEquals(first.username(), later.username());
        assertTrue(later.username().compareTo(first.username()) > 0); // later expiry, larger epoch
        assertNotEquals(first.credential(), bob.credential());
    }
}
