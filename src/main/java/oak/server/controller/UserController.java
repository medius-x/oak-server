package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.UserResponse;
import oak.server.dto.UserSummary;
import oak.server.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * User lookups — the roster (list) and the current-user profile. All endpoints require
 * a valid JWT (only {@code /api/auth/**} is public).
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    /**
     * All users of this instance as a compact roster. Returns {@link UserSummary} (no
     * email) — the public members list must not expose emails; the full profile stays
     * on {@code /api/users/me}.
     */
    @GetMapping
    public List<UserSummary> list() {
        return userRepository.findAllByOrderByUsernameAsc().stream()
                .map(UserSummary::from)
                .toList();
    }

    @GetMapping("/me")
    public UserResponse currentUser(@AuthenticationPrincipal UserDetails principal) {
        return userRepository.findByUsername(principal.getUsername())
                .map(UserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
