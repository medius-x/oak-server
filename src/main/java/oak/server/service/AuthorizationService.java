package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.entity.Channel;
import oak.server.entity.ChannelRoleOverride;
import oak.server.entity.Permission;
import oak.server.entity.Permissions;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a member's effective permissions and enforces role hierarchy. Centralizes every
 * permission check so services just call {@code requireServer} / {@code requireInChannel} /
 * {@code requireCanActOn} and get a {@link ResponseStatusException} (HTTP 403) on denial — matching
 * the codebase convention. Spring authorities ({@code ROLE_USER}) are intentionally left untouched:
 * permissions are read from the database by username, so a role change takes effect immediately
 * without reissuing the JWT.
 *
 * <p><b>Principal asymmetry:</b> a REST request principal is a {@code UserDetails} (→
 * {@code getUsername()}), a STOMP principal is the bare username string. Both feed the same
 * username-keyed lookups here.
 *
 * <p><b>Resolution</b> (Discord-style): the base mask is the OR of {@code @everyone} and the member's
 * roles; the OWNER role or the {@code ADMINISTRATOR} bit short-circuits to "all". Channel overrides
 * (per role, including {@code @everyone}) layer on top: a deny bit wins, then an allow bit, else the
 * base permission; {@code ADMINISTRATOR} bypasses overrides entirely.
 */
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ChannelRoleOverrideRepository overrideRepository;

    // --- lookups ---

    public Optional<User> findUser(String username) {
        if (username == null) {
            return Optional.empty();
        }
        // Eager-fetch roles: permission resolution reads user.getRoles(), which must work on threads
        // without a Hibernate session (STOMP clientInboundChannel has no OpenEntityManagerInView).
        return userRepository.findByUsernameWithRoles(username);
    }

    /** Loads the actor or rejects with 401-ish "not found" (authenticated principal must resolve). */
    public User requireUser(String username) {
        return findUser(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Actor not found"));
    }

    public boolean isOwner(User user) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        for (Role role : user.getRoles()) {
            if (role.getKind() == RoleKind.OWNER) {
                return true;
            }
        }
        return false;
    }

    // --- effective permissions ---

    /** The member's server-wide permission mask. OWNER / ADMINISTRATOR → all. */
    public long effectiveBase(User user) {
        if (user == null) {
            return Permissions.NONE;
        }
        long mask = everyonePermissions();
        Set<Role> roles = user.getRoles();
        if (roles != null) {
            for (Role role : roles) {
                if (role.getKind() == RoleKind.OWNER
                        || Permissions.has(role.getPermissions(), Permission.ADMINISTRATOR)) {
                    return Permissions.ALL;
                }
                mask |= role.getPermissions();
            }
        }
        return mask;
    }

    public boolean hasServer(User user, Permission permission) {
        return Permissions.has(effectiveBase(user), permission);
    }

    /**
     * Channel-scoped permission with override resolution. ADMINISTRATOR bypasses overrides; otherwise
     * gather the overrides of every role the member has (including {@code @everyone}): a deny bit
     * wins, then an allow bit, else fall back to the base permission.
     */
    public boolean hasInChannel(User user, Channel channel, Permission permission) {
        if (channel == null) {
            return hasServer(user, permission);
        }
        long base = effectiveBase(user);
        if (Permissions.has(base, Permission.ADMINISTRATOR)) {
            return true;
        }
        // Load @everyone once (its id marks the always-applying override); comparing override role ids
        // by id is proxy-safe (getId() does not initialize the LAZY role) — no N+1.
        Long everyoneRoleId = everyone().getId();
        Set<Long> memberRoleIds = roleIdsOf(user);

        long allow = Permissions.NONE;
        long deny = Permissions.NONE;
        List<ChannelRoleOverride> overrides = overrideRepository.findByChannelId(channel.getId());
        for (ChannelRoleOverride override : overrides) {
            Role overrideRole = override.getRole();
            if (overrideRole == null) {
                continue;
            }
            Long roleId = overrideRole.getId();
            if (everyoneRoleId.equals(roleId) || memberRoleIds.contains(roleId)) {
                allow |= override.getAllow();
                deny |= override.getDeny();
            }
        }
        if (Permissions.has(deny, permission)) {
            return false;
        }
        if (Permissions.has(allow, permission)) {
            return true;
        }
        return Permissions.has(base, permission);
    }

    private Set<Long> roleIdsOf(User user) {
        Set<Long> ids = new HashSet<>();
        if (user == null || user.getRoles() == null) {
            return ids;
        }
        for (Role role : user.getRoles()) {
            ids.add(role.getId());
        }
        return ids;
    }

    // --- hierarchy ---

    /** The member's highest role position (OWNER = {@link Integer#MAX_VALUE}); 0 if none. */
    public int topPosition(User user) {
        if (isOwner(user)) {
            return Integer.MAX_VALUE;
        }
        if (user == null || user.getRoles() == null) {
            return 0;
        }
        int top = 0;
        for (Role role : user.getRoles()) {
            if (role.getPosition() > top) {
                top = role.getPosition();
            }
        }
        return top;
    }

    /** Whether {@code actor} may act on {@code target}: strictly higher top role; OWNER-target is
     *  immune to everyone but the owner themselves. */
    public boolean canActOn(User actor, User target) {
        if (actor == null) {
            return false;
        }
        if (target == null) {
            return true;
        }
        if (isOwner(target)) {
            return actor.getId() != null && actor.getId().equals(target.getId());
        }
        return topPosition(actor) > topPosition(target);
    }

    /** Whether {@code actor} may grant/revoke {@code role}: strictly above its position; OWNER only
     *  for the OWNER role (transfer). */
    public boolean canAssignRole(User actor, Role role) {
        if (actor == null || role == null) {
            return false;
        }
        if (role.getKind() == RoleKind.OWNER) {
            return isOwner(actor);
        }
        return topPosition(actor) > role.getPosition();
    }

    // --- require* helpers (throw 403 on denial) ---

    public void requireServer(User user, Permission permission) {
        if (!hasServer(user, permission)) {
            throw forbidden("Missing permission: " + permission.name());
        }
    }

    public void requireInChannel(User user, Channel channel, Permission permission) {
        if (!hasInChannel(user, channel, permission)) {
            throw forbidden("Missing permission in this channel: " + permission.name());
        }
    }

    public void requireCanActOn(User actor, User target) {
        if (!canActOn(actor, target)) {
            throw forbidden("You cannot act on a member with an equal or higher role");
        }
    }

    public void requireCanAssignRole(User actor, Role role) {
        if (!canAssignRole(actor, role)) {
            throw forbidden("You cannot assign a role at or above your highest role");
        }
    }

    // --- everyone role access ---

    private long everyonePermissions() {
        return everyone().getPermissions();
    }

    private Role everyone() {
        return roleRepository.findByKind(RoleKind.EVERYONE)
                .orElseThrow(() -> new IllegalStateException(
                        "@everyone role missing — RoleSeeder did not run"));
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
}
