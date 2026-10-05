package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.entity.Channel;
import oak.server.entity.ChannelParticipant;
import oak.server.entity.ChannelRoleOverride;
import oak.server.entity.ChannelType;
import oak.server.entity.ChannelUserOverride;
import oak.server.entity.Permission;
import oak.server.entity.Permissions;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.ChannelParticipantRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelUserOverrideRepository;
import oak.server.repository.DiscussionRepository;
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
 * then apply in LAYERS (see {@link #resolveChannelMask}): the channel's {@code @everyone} row, then
 * the member's own role rows (deny beats allow within the layer), then the member's personal row —
 * a later layer overrides an earlier one, which is what makes private channels possible (a role or
 * member ALLOW beats an {@code @everyone} DENY). Finally, {@code VIEW_CHANNEL} is the master gate:
 * without it every other channel permission resolves to false.
 */
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ChannelRoleOverrideRepository overrideRepository;
    /** Member-level (per-user) channel overrides — the top resolution layer of
     *  {@link #resolveChannelMask}. */
    private final ChannelUserOverrideRepository userOverrideRepository;
    /** DM membership — the single authority for DM / group-DM access (the branch in {@link #hasInChannel}). */
    private final ChannelParticipantRepository participantRepository;
    /** Discussion rows — the DISCUSSION branch of {@link #hasInChannel} resolves through the parent. */
    private final DiscussionRepository discussionRepository;
    private final ChannelRepository channelRepository;

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
     * Channel-scoped permission with layered override resolution and the VIEW_CHANNEL master gate.
     * ADMINISTRATOR bypasses overrides entirely (admins see and use every non-DM channel). DM /
     * group-DM stays membership-only. Every other permission requires VIEW_CHANNEL — a hidden
     * channel denies everything, even where the @everyone base still grants READ/SEND/CONNECT.
     */
    public boolean hasInChannel(User user, Channel channel, Permission permission) {
        if (channel == null) {
            return hasServer(user, permission);
        }
        // DM / group-DM: membership is the only authority. A participant gets every capability (read,
        // send, attach, mention); a non-participant gets nothing — even the instance OWNER. Placed
        // before the ADMINISTRATOR short-circuit on purpose, so DMs stay private from admins. This
        // single branch covers every existing call site (message read/send/attach, read-state, perms).
        if (channel.getType() == ChannelType.DM || channel.getType() == ChannelType.GROUP_DM) {
            return participantRepository.existsByChannelIdAndUserId(channel.getId(), user.getId());
        }
        // A DISCUSSION channel owns no permission rows of its own: everything (VIEW master gate,
        // overrides, READ/SEND/ATTACH — and MANAGE_MESSAGES for the close gate) resolves through
        // its PARENT channel, exactly as it would be asked there. One hop — a parent is always
        // TEXT (the creation gate), so this cannot recurse. An orphaned row (parent deleted
        // mid-flight) denies everything.
        if (channel.getType() == ChannelType.DISCUSSION) {
            return discussionRepository.findByChannelId(channel.getId())
                    .flatMap(discussion -> channelRepository.findById(discussion.getParentChannelId()))
                    .map(parent -> hasInChannel(user, parent, permission))
                    .orElse(false);
        }
        long mask = resolveChannelMask(user, channel);
        boolean canView = Permissions.has(mask, Permission.VIEW_CHANNEL);
        return permission == Permission.VIEW_CHANNEL
                ? canView
                : canView && Permissions.has(mask, permission);
    }

    /**
     * The member's effective permission bitmask on one TEXT/VOICE channel, layered Discord-style:
     * <ol>
     *   <li>base = OR of {@code @everyone} and the member's roles (OWNER/ADMINISTRATOR → all,
     *       returned immediately — admins bypass every override).</li>
     *   <li>Layer 1 — the channel's {@code @everyone} override row.</li>
     *   <li>Layer 2 — the override rows of the member's own roles (allows OR-ed, denies OR-ed, deny
     *       wins within the layer).</li>
     *   <li>Layer 3 — the member's personal override row, applied last: a member allow beats any
     *       role/@everyone deny.</li>
     * </ol>
     * Later layers override earlier ones ({@code (mask | allow) & ~deny} per layer) — the property
     * the private-channel grants depend on. DM channels are NOT mask-resolvable: their authority
     * is membership (see {@link #hasInChannel}), not a bitmask.
     */
    public long resolveChannelMask(User user, Channel channel) {
        // The mask path mirrors the hasInChannel delegation: a DISCUSSION channel's mask IS its
        // parent's (defensive — the listing/rename paths never hand a discussion here, but any
        // future caller must not silently resolve an override-less mask).
        if (channel.getType() == ChannelType.DISCUSSION) {
            return discussionRepository.findByChannelId(channel.getId())
                    .flatMap(discussion -> channelRepository.findById(discussion.getParentChannelId()))
                    .map(parent -> resolveChannelMask(user, parent))
                    .orElse(Permissions.NONE);
        }
        long mask = effectiveBase(user);
        if (Permissions.has(mask, Permission.ADMINISTRATOR)) {
            return Permissions.ALL;
        }
        // Load @everyone once (its id marks the always-applying override); comparing override role ids
        // by id is proxy-safe (getId() does not initialize the LAZY role) — no N+1.
        Long everyoneRoleId = everyone().getId();
        Set<Long> memberRoleIds = roleIdsOf(user);

        long everyoneAllow = Permissions.NONE;
        long everyoneDeny = Permissions.NONE;
        long roleAllow = Permissions.NONE;
        long roleDeny = Permissions.NONE;
        List<ChannelRoleOverride> overrides = overrideRepository.findByChannelId(channel.getId());
        for (ChannelRoleOverride override : overrides) {
            Role overrideRole = override.getRole();
            if (overrideRole == null) {
                continue;
            }
            Long roleId = overrideRole.getId();
            if (everyoneRoleId.equals(roleId)) {
                everyoneAllow |= override.getAllow();
                everyoneDeny |= override.getDeny();
            } else if (memberRoleIds.contains(roleId)) {
                roleAllow |= override.getAllow();
                roleDeny |= override.getDeny();
            }
        }
        mask = (mask | everyoneAllow) & ~everyoneDeny;
        mask = (mask | roleAllow) & ~roleDeny;
        ChannelUserOverride userOverride = userOverrideRepository
                .findByChannelIdAndUserId(channel.getId(), user.getId()).orElse(null);
        if (userOverride != null) {
            mask = (mask | userOverride.getAllow()) & ~userOverride.getDeny();
        }
        return mask;
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

    // --- private-message restriction (RESTRICT_PRIVATE_MESSAGES — the one subtractive permission) ---

    /**
     * Whether the member is barred from direct messages: some explicitly held role carries the
     * {@link Permission#RESTRICT_PRIVATE_MESSAGES} bit in its RAW mask. Deliberately NOT resolved
     * through {@link #effectiveBase}: the OR-of-roles model cannot subtract a bit, its
     * ADMINISTRATOR/OWNER short-circuit to ALL would make every admin look restricted, and the bit
     * must never leak into the {@code /users/me/permissions} name list (it is not a grant). The
     * @everyone copy of the bit is ignored — restriction is expressed per held role only, so a
     * single checkbox on @everyone cannot silence the whole instance. OWNER and ADMINISTRATOR
     * holders are immune (the privilege model everywhere else); to restrict an admin, first take
     * the admin bit away.
     */
    public boolean dmRestricted(User user) {
        if (isOwner(user) || hasServer(user, Permission.ADMINISTRATOR)) {
            return false;
        }
        Set<Role> roles = user.getRoles();
        if (roles == null) {
            return false;
        }
        for (Role role : roles) {
            // The OWNER role's mask is ALL — it "carries" every bit without meaning any of them.
            if (role.getKind() == RoleKind.OWNER) {
                continue;
            }
            if (Permissions.has(role.getPermissions(), Permission.RESTRICT_PRIVATE_MESSAGES)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The send-side DM gate for existing conversations. In a 1:1 DM the restriction freezes BOTH
     * sides — a message to a restricted member would be a received one — while a group DM only
     * silences the restricted member themselves (one restricted member must not freeze the chat
     * for everyone else). Reads (history, read-state, subscriptions) are NOT gated: existing
     * conversations stay readable. Server channels are a no-op — their authority is SEND_MESSAGES.
     */
    public void requireDmSendAllowed(User author, Channel channel) {
        if (channel == null
                || (channel.getType() != ChannelType.DM && channel.getType() != ChannelType.GROUP_DM)) {
            return;
        }
        if (dmRestricted(author)) {
            throw forbidden("You are not allowed to send private messages");
        }
        if (channel.getType() == ChannelType.DM) {
            for (ChannelParticipant participant : participantRepository.findByChannelId(channel.getId())) {
                User member = participant.getUser();
                if (!member.getId().equals(author.getId()) && dmRestricted(member)) {
                    throw forbidden("This member cannot receive private messages");
                }
            }
        }
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
