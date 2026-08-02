package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.AuthResponse;
import oak.server.dto.LoginRequest;
import oak.server.dto.RegisterRequest;
import oak.server.dto.UserResponse;
import oak.server.entity.RefreshToken;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.RefreshTokenRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.security.JwtProperties;
import oak.server.security.JwtTokenProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Registration, login and token refresh. Passwords are hashed with BCrypt. A successful login or
 * refresh issues a signed short-lived access JWT plus a long-lived opaque refresh token (stored
 * hashed, rotated on each refresh so a leaked refresh is usable at most once). Refresh tokens form
 * a family per login; reuse of an already-rotated token revokes the whole family (reuse-detection).
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final RoleRepository roleRepository;
    private final ReadStateService readStateService;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw conflict("Username already taken: " + request.username());
        }
        // The first member of a fresh instance becomes the OWNER (the superuser); later members get
        // only @everyone by default. (RoleSeeder ensures the OWNER role already exists at startup.)
        boolean firstUser = userRepository.count() == 0;
        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password()));
        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Guards against the rare race between the exists-check and the INSERT
            // (the unique constraint is the real authority).
            throw conflict("Username already exists");
        }
        if (firstUser) {
            // saved is effectively final here, so the lambda can capture it.
            roleRepository.findByKind(RoleKind.OWNER).ifPresent(ownerRole -> grantOwner(saved, ownerRole));
        }
        // Seed read-state so the pre-existing archive is not "unread" on day one (Discord-faithful):
        // every existing channel's pointer starts at its current latest message.
        readStateService.seedInitialReadStates(saved);
        return UserResponse.from(saved);
    }

    /** Assigns the OWNER role to the first member (writes the user_roles join row). */
    private void grantOwner(User user, Role ownerRole) {
        user.getRoles().add(ownerRole);
        userRepository.save(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            User user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> unauthorized("Invalid username or password"));
            if (user.isBanned()) {
                throw forbidden("This account is banned");
            }
            return issueTokens(user, newFamilyId());
        } catch (BadCredentialsException e) {
            throw unauthorized("Invalid username or password");
        }
    }

    /**
     * Rotates a refresh token: revokes the presented one and issues a fresh access JWT + a fresh
     * refresh token in the <i>same family</i>. A reused (already revoked) token is treated as a
     * theft signal — the entire family is revoked (the attacker's copy and every downstream token
     * die together) — and rejected with 401. A merely expired token is rejected with 401 <b>without</b>
     * touching the family (expiry is not theft: the user's other devices keep working).
     *
     * <p>{@code noRollbackFor = ResponseStatusException.class} keeps the family-wide revocation from
     * being rolled back: by default Spring marks the tx rollback-only when the 401 propagates, which
     * would undo the very revoke we just performed.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
                .orElseThrow(() -> unauthorized("Invalid refresh token"));

        if (stored.isRevoked()) {
            // Reuse of an already-rotated token → revoke the whole family so the breach is contained.
            String familyId = stored.getFamilyId();
            if (familyId != null) {
                refreshTokenRepository.findByFamilyId(familyId)
                        .forEach(token -> token.setRevoked(true));
            }
            throw unauthorized("Refresh token reused — token family revoked");
        }
        if (stored.getUser().isBanned()) {
            // A banned account must not rotate tokens — every access JWT is already 401'd by the filter,
            // so refuse the refresh and revoke the family to stop the churn (noRollbackFor keeps the
            // revoke, same as the reuse branch above).
            String familyId = stored.getFamilyId();
            if (familyId != null) {
                refreshTokenRepository.findByFamilyId(familyId).forEach(token -> token.setRevoked(true));
            }
            throw unauthorized("This account is banned");
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw unauthorized("Refresh token expired");
        }
        stored.setRevoked(true); // rotate: the presented refresh is single-use
        return issueTokens(stored.getUser(), stored.getFamilyId());
    }

    /** Issues a fresh access JWT + a new (persisted, hashed) refresh token for {@code user}. */
    private AuthResponse issueTokens(User user, String familyId) {
        String accessToken = tokenProvider.generateToken(user.getUsername());
        String rawRefresh = issueRefresh(user, familyId);
        return new AuthResponse(accessToken, rawRefresh, "Bearer", tokenProvider.accessTokenExpirationSeconds());
    }

    private String issueRefresh(User user, String familyId) {
        String raw = newRawToken();
        Instant expiresAt = Instant.now().plus(jwtProperties.refreshTokenExpiration());
        refreshTokenRepository.save(new RefreshToken(user, hashToken(raw), expiresAt, familyId));
        return raw;
    }

    /** A fresh family id for a new login lineage (refreshes continue the same family). */
    private static String newFamilyId() {
        return UUID.randomUUID().toString();
    }

    private static String newRawToken() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /** SHA-256 hex of the raw token; stored instead of the raw token so a DB leak is unusable. */
    private static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 not available", unavailable);
        }
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
}
