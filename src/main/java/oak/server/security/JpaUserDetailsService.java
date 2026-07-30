package oak.server.security;

import lombok.RequiredArgsConstructor;
import oak.server.entity.User;
import oak.server.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads {@link User} records from PostgreSQL into Spring Security {@link UserDetails}.
 * Every registered account currently has the {@code ROLE_USER} authority.
 */
@Service
@RequiredArgsConstructor
public class JpaUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .map(this::toUserDetails)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private UserDetails toUserDetails(User user) {
        return new PrincipalUser(user.getUsername(), user.getPasswordHash(), user.isBanned());
    }
}
