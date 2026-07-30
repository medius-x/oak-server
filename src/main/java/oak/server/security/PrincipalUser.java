package oak.server.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

/**
 * The authenticated principal — a Spring Security {@link User} carrying the OAK-specific {@code banned}
 * flag so {@link JwtAuthenticationFilter} can reject a still-valid access JWT of a banned account on
 * <em>every</em> REST request (ban then takes effect immediately, not only on the next login/CONNECT).
 *
 * <p>{@code banned} is kept OUT of {@code enabled}/{@code accountNonLocked} on purpose:
 * {@link oak.server.service.AuthService#login} authenticates through {@code AuthenticationManager}, whose
 * {@code DaoAuthenticationProvider} would otherwise raise a non-caught {@code DisabledException} (login
 * catches only {@code BadCredentialsException} → 500). Login instead reads {@code banned} straight from
 * the JPA entity. So the flag rides here only for the filter; it never influences Spring's status checks
 * (all four pass as {@code true}).
 */
public class PrincipalUser extends User {

    private final boolean banned;

    public PrincipalUser(String username, String passwordHash, boolean banned) {
        super(username, passwordHash, true, true, true, true, AuthorityUtils.createAuthorityList("ROLE_USER"));
        this.banned = banned;
    }

    /** Whether this account is banned — checked by {@link JwtAuthenticationFilter} to reject REST early. */
    @JsonIgnore
    public boolean isBanned() {
        return banned;
    }
}
