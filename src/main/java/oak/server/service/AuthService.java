package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.AuthResponse;
import oak.server.dto.LoginRequest;
import oak.server.dto.RegisterRequest;
import oak.server.dto.UserResponse;
import oak.server.entity.User;
import oak.server.repository.UserRepository;
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

/**
 * Registration + login. Passwords are hashed with BCrypt; successful login
 * returns a signed JWT access token.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;

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

    public AuthResponse login(LoginRequest request) {
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            String token = tokenProvider.generateToken(authentication.getName());
            return new AuthResponse(token, "Bearer", tokenProvider.accessTokenExpirationSeconds());
        } catch (BadCredentialsException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
