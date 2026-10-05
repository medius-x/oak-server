package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.config.TurnProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints short-lived coturn REST-API credentials (the {@code --use-auth-secret} ticket
 * scheme): username is {@code "<unix-expiry>:<principal>"}, password is
 * {@code base64(HMAC-SHA1(staticAuthSecret, username))}. coturn validates the timestamp
 * and the HMAC on its side and never sees per-user state; the credentials die at the
 * expiry embedded in the username, so a leaked one is time-boxed (unlike the old static
 * pair that lived forever and was identical for every member).
 */
@Component
@RequiredArgsConstructor
public class TurnCredentialIssuer {

    private final TurnProperties turnProperties;

    /** A freshly issued TURN credential: the two fields an RTCIceServer takes verbatim. */
    public record TurnCredential(String username, String credential) {
    }

    public TurnCredential issue(String principalName, Instant now) {
        long expiryEpochSecond = now.getEpochSecond() + turnProperties.credentialTtl().toSeconds();
        String username = expiryEpochSecond + ":" + principalName;
        return new TurnCredential(username, Base64.getEncoder().encodeToString(
                hmacSha1(turnProperties.staticAuthSecret(), username)));
    }

    /** HMAC-SHA1(secret, value) — package-private for the RFC 2202 unit test vector. */
    static byte[] hmacSha1(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // HmacSHA1 is mandated by the JCA spec; a JRE without it cannot boot Spring either.
            throw new IllegalStateException("HmacSHA1 unavailable", impossible);
        }
    }
}
