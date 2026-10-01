package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.controller.InviteController;
import oak.server.dto.AuthResponse;
import oak.server.dto.LoginRequest;
import oak.server.dto.RegisterRequest;
import oak.server.dto.UserResponse;
import oak.server.entity.Invite;
import oak.server.entity.RefreshToken;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.RefreshTokenRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.repository.InviteRepository;
import oak.server.security.JwtProperties;
import oak.server.security.JwtTokenProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
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
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final RoleRepository roleRepository;
    private final ReadStateService readStateService;
    private final InviteRepository inviteRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Registration. A FRESH instance (no users yet) bootstraps its OWNER with no secret —
     * the pre-provisioning path; every later registration on a non-empty instance REQUIRES
     * a valid unexpired invite secret. The single-use claim is an atomic guarded DELETE
     * run AFTER the user INSERT: a lost race throws the 403 that rolls the whole tx back,
     * so the just-inserted user goes away and the link is not spent by a failure (and the
     * INSERT's own DIVE races die before the claim, never spending it). A spent link stops
     * existing — repeating it reads as an unknown secret.
     *
     * <p>The nickname pre-check is unconditional even in the nickname-equals-login case: a
     * login colliding with someone's NICKNAME would otherwise die on UNIQUE(nickname) with
     * a misleading "Username already exists" (the users.email lesson, in message form).
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw conflict("Username already taken: " + request.username());
        }
        String nickname = request.nickname() == null || request.nickname().isBlank()
                ? request.username()
                : request.nickname().trim();
        if (userRepository.existsByNickname(nickname)) {
            throw conflict("Nickname is already taken");
        }
        // The first member of a fresh instance becomes the OWNER (the superuser); later members get
        // only @everyone by default. (RoleSeeder ensures the OWNER role already exists at startup.)
        boolean firstUser = userRepository.count() == 0;
        String inviteSecret = request.inviteSecret() == null ? null : request.inviteSecret().trim();
        boolean hasInviteSecret = inviteSecret != null && !inviteSecret.isEmpty();
        if (!firstUser && !hasInviteSecret) {
            throw forbidden("An invite link is required to register on this server");
        }
        if (!firstUser) {
            // Human-readable pre-check; the authority against the registration race is the claim.
            Invite invite = inviteRepository.findBySecret(inviteSecret)
                    .orElseThrow(() -> forbidden("This invite link is not valid"));
            if (!invite.isActive(Instant.now())) {
                throw forbidden("This invite link has expired or was revoked");
            }
        }
        User user = new User(
                request.username(),
                passwordEncoder.encode(request.password()));
        user.setNickname(nickname);
        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Guards against the rare race between the exists-checks and the INSERT
            // (the unique constraints are the real authority).
            throw conflict("Username already exists");
        }
        if (firstUser) {
            // saved is effectively final here, so the lambda can capture it.
            roleRepository.findByKind(RoleKind.OWNER).ifPresent(ownerRole -> grantOwner(saved, ownerRole));
        } else if (inviteRepository.claim(inviteSecret, Instant.now()) == 0) {
            // Lost the single-use race (spent/revoked/expired between the pre-check and here).
            // The 403 rolls the tx back — the user INSERT and the claim go away together.
            throw forbidden("This invite link is not valid");
        } else {
            // A registration consumed a link: the admins' Invites list drops it live.
            // AFTER COMMIT — an in-tx ping lets subscribers refetch before the claim
            // is visible and the spent link would linger until the next refetch.
            InviteController.publishPingAfterCommit(messagingTemplate);
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
            if (user.isRemoved()) {
                throw forbidden("This account was removed from the server");
            }
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
                .orElseThrow(() -> {
                    log.warn("[auth-refresh] rejected UNKNOWN token fp={} from {}",
                            fingerprint(rawRefreshToken), remoteAddress());
                    return unauthorized("Invalid refresh token");
                });

        if (stored.isRevoked()) {
            // Reuse of an already-rotated token → revoke the whole family so the breach is contained.
            // The log is the forensics for the periodic "session expired" mystery: tokenAge shows HOW
            // STALE the re-presented copy was (an ancient copy = some holder never refreshed — a second
            // process/device; a fresh one = a genuine in-flight race), fp matches the client's own log.
            String familyId = stored.getFamilyId();
            log.warn("[auth-refresh] REUSE — user={} family={} presentedId={} tokenAge={} fp={} from {}: family revoked",
                    stored.getUser().getUsername(), familyId, stored.getId(),
                    Duration.between(stored.getCreatedAt(), Instant.now()),
                    fingerprint(rawRefreshToken), remoteAddress());
            if (familyId != null) {
                refreshTokenRepository.findByFamilyId(familyId)
                        .forEach(token -> token.setRevoked(true));
            }
            throw unauthorized("Refresh token reused — token family revoked");
        }
        if (stored.getUser().isRemoved()) {
            // A removed account must not rotate tokens — the removal already renamed the account, so the
            // subject of any access JWT is dead anyway; refuse and revoke the family to stop the churn
            // (noRollbackFor keeps the revoke, same as the reuse branch above).
            String familyId = stored.getFamilyId();
            log.warn("[auth-refresh] REMOVED — user={} family={} presentedId={} from {}: family revoked",
                    stored.getUser().getUsername(), familyId, stored.getId(), remoteAddress());
            if (familyId != null) {
                refreshTokenRepository.findByFamilyId(familyId).forEach(token -> token.setRevoked(true));
            }
            throw unauthorized("This account was removed from the server");
        }
        if (stored.getUser().isBanned()) {
            // A banned account must not rotate tokens — every access JWT is already 401'd by the filter,
            // so refuse the refresh and revoke the family to stop the churn (noRollbackFor keeps the
            // revoke, same as the reuse branch above).
            String familyId = stored.getFamilyId();
            log.warn("[auth-refresh] BANNED — user={} family={} presentedId={} from {}: family revoked",
                    stored.getUser().getUsername(), familyId, stored.getId(), remoteAddress());
            if (familyId != null) {
                refreshTokenRepository.findByFamilyId(familyId).forEach(token -> token.setRevoked(true));
            }
            throw unauthorized("This account is banned");
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            log.info("[auth-refresh] rejected EXPIRED token user={} presentedId={} tokenAge={} fp={} from {}",
                    stored.getUser().getUsername(), stored.getId(),
                    Duration.between(stored.getCreatedAt(), Instant.now()),
                    fingerprint(rawRefreshToken), remoteAddress());
            throw unauthorized("Refresh token expired");
        }
        stored.setRevoked(true); // rotate: the presented refresh is single-use
        AuthResponse response = issueTokens(stored.getUser(), stored.getFamilyId());
        log.info("[auth-refresh] rotated user={} family={} presentedId={} tokenAge={} fp={} from {}",
                stored.getUser().getUsername(), stored.getFamilyId(), stored.getId(),
                Duration.between(stored.getCreatedAt(), Instant.now()),
                fingerprint(rawRefreshToken), remoteAddress());
        return response;
    }

    /**
     * Self-service password change: verifies the current password, stores a fresh BCrypt hash, revokes
     * every refresh token of this user (so a password change logs out all other sessions), and issues
     * a fresh access + refresh pair for THIS session (a new family) — the caller keeps working without
     * a re-login. The access JWT is stateless and cannot be revoked, so other devices' access tokens
     * stay valid up to their 8h expiry, but their now-revoked refresh dies on the next refresh attempt.
     */
    @Transactional
    public AuthResponse changePassword(String username, String oldPassword, String newPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (newPassword.equals(oldPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        // Revoke every session of this user — a password change invalidates all other devices.
        refreshTokenRepository.findByUser(user).forEach(token -> token.setRevoked(true));
        return issueTokens(user, newFamilyId());
    }

    /**
     * Self-service username rename. Uniqueness: explicit {@code existsByUsername} check → 409, plus
     * the unique constraint as the real authority against the check/INSERT race (same belt-and-braces
     * as {@link #register}). Token rotation mirrors {@link #changePassword}: the JWT subject IS the
     * username, so after the rename every previously issued access token stops resolving and every
     * refresh token is revoked — this session gets a fresh pair (a new family) in the response and
     * must also restart its WebSocket (the STOMP principal was stamped from the old subject).
     * Permission enforcement (CHANGE_NICKNAME) lives in the controller, like MANAGE_SERVER in
     * ServerProfileController.
     */
    @Transactional
    public AuthResponse changeUsername(User user, String newUsername) {
        String trimmed = newUsername.trim();
        if (trimmed.equals(user.getUsername())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "New username must be different from the current one");
        }
        if (userRepository.existsByUsername(trimmed)) {
            throw conflict("Username already taken: " + trimmed);
        }
        user.setUsername(trimmed);
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException race) {
            // The exists-check above races a concurrent registration on the same name; the unique
            // constraint is the real authority.
            throw conflict("Username already exists");
        }
        refreshTokenRepository.findByUser(user).forEach(token -> token.setRevoked(true));
        return issueTokens(user, newFamilyId());
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

    /** 8 chars of the RAW token — enough to correlate an {@code [auth-refresh]} line with the
     *  client's own log (which prints the same fingerprint), far too little to reconstruct it. */
    private static String fingerprint(String rawToken) {
        if (rawToken == null) {
            return "null";
        }
        return rawToken.substring(0, Math.min(8, rawToken.length()));
    }

    /** The caller's address — distinguishes machines if a token ever shows up from somewhere
     *  unexpected. "no-request" covers a future non-HTTP caller (none today). */
    private static String remoteAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return "no-request";
    }
}
