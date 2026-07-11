package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.AuthResponse;
import oak.server.dto.LoginRequest;
import oak.server.dto.RegisterRequest;
import oak.server.dto.UserResponse;
import oak.server.entity.RefreshToken;
import oak.server.entity.User;
import oak.server.repository.RefreshTokenRepository;
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

/**
 * Registration, login and token refresh. Passwords are hashed with BCrypt. A successful login or
 * refresh issues a signed short-lived access JWT plus a long-lived opaque refresh token (stored
 * hashed, rotated on each refresh so a leaked refresh is usable at most once).
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

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw conflict("Username already taken: " + request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw conflict("Email already registered: " + request.email());
        }
        User user = new User(
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()));
        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Guards against the rare race between the exists-checks and the INSERT
            // (the unique constraints are the real authority).
            throw conflict("Username or email already exists");
        }
        return UserResponse.from(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            User user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> unauthorized("Invalid username or password"));
            return issueTokens(user);
        } catch (BadCredentialsException e) {
            throw unauthorized("Invalid username or password");
        }
    }

    /**
     * Rotates a refresh token: revokes the presented one and issues a fresh access JWT + a fresh
     * refresh token. A reused (already revoked) or expired refresh token is rejected with 401.
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
                .orElseThrow(() -> unauthorized("Invalid refresh token"));
        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw unauthorized("Refresh token expired or revoked");
        }
        stored.setRevoked(true); // rotate: the presented refresh is single-use
        return issueTokens(stored.getUser());
    }

    /** Issues a fresh access JWT + a new (persisted, hashed) refresh token for {@code user}. */
    private AuthResponse issueTokens(User user) {
        String accessToken = tokenProvider.generateToken(user.getUsername());
        String rawRefresh = issueRefresh(user);
        return new AuthResponse(accessToken, rawRefresh, "Bearer", tokenProvider.accessTokenExpirationSeconds());
    }

    private String issueRefresh(User user) {
        String raw = newRawToken();
        Instant expiresAt = Instant.now().plus(jwtProperties.refreshTokenExpiration());
        refreshTokenRepository.save(new RefreshToken(user, hashToken(raw), expiresAt));
        return raw;
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
}
