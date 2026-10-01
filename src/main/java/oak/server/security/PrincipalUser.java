package oak.server.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

/**
 * The authenticated principal — a Spring Security {@link User} carrying the OAK-specific {@code banned}
 * and {@code removed} flags so {@link JwtAuthenticationFilter} can reject a still-valid access JWT of a
 * banned or removed account on <em>every</em> REST request (the state change then takes effect
 * immediately, not only on the next login/CONNECT).
 *
 * <p>The flags are kept OUT of {@code enabled}/{@code accountNonLocked} on purpose:
 * {@link oak.server.service.AuthService#login} authenticates through {@code AuthenticationManager}, whose
 * {@code DaoAuthenticationProvider} would otherwise raise a non-caught {@code DisabledException} (login
 * catches only {@code BadCredentialsException} → 500). Login instead reads the flags straight from
 * the JPA entity. So they ride here only for the filter; they never influence Spring's status checks
 * (all four pass as {@code true}).
 */
public class PrincipalUser extends User {

    private final boolean banned;
    private final boolean removed;

    public PrincipalUser(String username, String passwordHash, boolean banned, boolean removed) {
        super(username, passwordHash, true, true, true, true, AuthorityUtils.createAuthorityList("ROLE_USER"));
        this.banned = banned;
        this.removed = removed;
    }

    /** Whether this account is banned — checked by {@link JwtAuthenticationFilter} to reject REST early. */
    @JsonIgnore
    public boolean isBanned() {
        return banned;
    }

    /** Whether this account was permanently removed from the server — same filter gate as {@link #isBanned()}. */
    @JsonIgnore
    public boolean isRemoved() {
        return removed;
    }
}
