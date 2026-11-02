package oak.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one-shot migration token of the server-to-server transfer leg (user decision
 * 2026-11-02, variant B): the NEW server pulls the built backup archive straight from the
 * OLD one with {@code curl}, authorized by a short-lived token instead of any login —
 * root credentials of the old server are deliberately NEVER handed to the new one.
 *
 * <p><b>In-memory single slot, no database</b>: the token is issued per migration (a new
 * issue silently displaces the previous one), and the whole slot dies with the process. A
 * restart of the old server mid-migration fails the leg honestly — the client falls back
 * to the through-this-computer route, which is always available. No @Scheduled cleanup:
 * an expired slot is simply rejected lazily ({@code isValid} checks the deadline) and gets
 * overwritten by the next issue.
 *
 * <p><b>Token shape</b>: 32 bytes of {@link SecureRandom} → base64url without padding
 * (the {@code newRawToken} idiom); only its SHA-256 hash is kept in memory, and
 * comparisons are constant-time. Logs carry an 8-character fingerprint of the raw value —
 * never the token itself. The TTL (2 hours) covers a full provisioning run BEFORE the
 * restore stage (15–40 min on a slow box) plus the transfer of a multi-gigabyte archive.
 *
 * <p><b>Reuse semantics</b>: the token is deliberately MULTI-use within its TTL — the
 * reachability probe and the ranged {@code curl -C -} retries of one transfer must all
 * validate. The "one-shot" property of the migration comes from the archive's own
 * deliver-and-delete rule, not from consuming the token.
 */
@Slf4j
@Service
public class MigrationTransferService {

    /** Set from tests to bend time; production uses the system clock. */
    Clock clock = Clock.systemUTC();

    private record IssuedToken(byte[] tokenHash, Instant expiresAt) {}

    private volatile IssuedToken slot = null;
    /** One transfer stream at a time: a second concurrent download of the single-slot
     *  archive would interleave range writes over one file — the caller answers 429. */
    private final AtomicBoolean transferInFlight = new AtomicBoolean(false);

    /** The freshly issued token: the RAW value rides this response exactly once. */
    public record Issued(String token, Instant expiresAt) {}

    /** Issues (or re-issues — displacing any previous token) a migration token. */
    public Issued issue() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = clock.instant().plus(tokenTtl());
        slot = new IssuedToken(sha256(token), expiresAt);
        log.info("[migration-transfer] token issued {}… (expires {})", fingerprint(token), expiresAt);
        return new Issued(token, expiresAt);
    }

    /** Constant-time validation against the live slot (hash + deadline). */
    public boolean isValid(String rawToken) {
        IssuedToken current = slot;
        if (rawToken == null || current == null || clock.instant().isAfter(current.expiresAt())) {
            return false;
        }
        return MessageDigest.isEqual(current.tokenHash(), sha256(rawToken));
    }

    /** Withdraws the token (the migration ended, was cancelled, or fell back to the
     *  through-this-computer route). Idempotent. */
    public void revoke() {
        slot = null;
        transferInFlight.set(false);
        log.info("[migration-transfer] token revoked");
    }

    /** Claims the single transfer slot; false while another delivery stream is alive
     *  (the caller answers 429). Must be balanced by {@link #endTransfer()} when the
     *  stream closes — including on error paths. */
    public boolean beginTransfer(String rawToken) {
        if (!isValid(rawToken)) {
            return false;
        }
        return transferInFlight.compareAndSet(false, true);
    }

    /** Releases the transfer slot (the delivery stream closed — drained or aborted). */
    public void endTransfer() {
        transferInFlight.set(false);
    }

    /** Visible for tests: the deadline window a fresh token carries. */
    Duration tokenTtl() {
        return Duration.ofHours(2);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available", unavailable);
        }
    }

    /** The log-safe fingerprint of a raw token (first 8 chars — the refresh-token idiom). */
    private static String fingerprint(String token) {
        return token.substring(0, Math.min(8, token.length()));
    }
}
