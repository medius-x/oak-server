package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AuthResponse;
import oak.server.dto.LoginRequest;
import oak.server.dto.RefreshRequest;
import oak.server.dto.RegisterRequest;
import oak.server.dto.UserResponse;
import oak.server.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public auth endpoints: register a new account and log in to receive a JWT.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@RequestBody @Valid RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody @Valid LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@RequestBody RefreshRequest request) {
        // Public (under /api/auth/**); the refresh token itself is the credential. Rotates it.
        return authService.refresh(request.refreshToken());
    }

    /** M7: revokes the PRESENTED refresh token (see AuthService.logout). 204, always idempotent. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    /**
     * M7: revokes EVERY refresh token of the caller (all sessions, all devices). The path is
     * public ({@code /api/auth/**}) but the JwtAuthenticationFilter still resolves a Bearer JWT
     * on it — no principal means nothing to revoke, which is a 401, not a silent 204.
     */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        authService.logoutAll(principal.getUsername());
    }
}
