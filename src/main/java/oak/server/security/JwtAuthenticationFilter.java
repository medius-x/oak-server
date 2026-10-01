package oak.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Extracts a Bearer JWT from the Authorization header, validates it, loads the
 * user and sets the Spring Security context for the remainder of the request.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (StringUtils.hasText(token) && tokenProvider.isValid(token)) {
            String username = tokenProvider.extractUsername(token);
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                // A cryptographically valid JWT whose subject no longer resolves (the user was renamed
                // — the subject IS the username — or deleted) is just an unauthenticated request, not a
                // 500: treat it exactly like an absent token and let the security chain answer 401.
                // The rename flow makes this a NORMAL, expected state for up to 8h of token lifetime.
                UserDetails userDetails;
                try {
                    userDetails = userDetailsService.loadUserByUsername(username);
                } catch (UsernameNotFoundException renamedOrDeleted) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "unknown user");
                    return;
                }
                // A banned or removed account's still-valid access JWT must not reach any REST endpoint:
                // the state change takes effect now, not on the next login/CONNECT. Short-circuit (no
                // filterChain.doFilter) so the protected resource is never served.
                if (userDetails instanceof PrincipalUser principal && (principal.isBanned() || principal.isRemoved())) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, principal.isBanned() ? "banned" : "removed");
                    return;
                }
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
