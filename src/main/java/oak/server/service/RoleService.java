package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.CreateRoleRequest;
import oak.server.dto.ReorderRolesRequest;
import oak.server.dto.RoleEvent;
import oak.server.dto.RoleResponse;
import oak.server.dto.UpdateRoleRequest;
import oak.server.entity.Permission;
import oak.server.entity.Permissions;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.websocket.VoiceAccessEnforcer;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Role CRUD + reorder, all gated by {@link Permission#MANAGE_ROLES} and role hierarchy. Custom roles
 * have dense integer positions managed by {@link #reorder}; a newly created role is placed at the top
 * of the custom ones. The {@code @everyone} and {@code OWNER} built-ins are protected (cannot be
 * deleted/renamed; {@code @everyone}'s permissions may be edited, {@code OWNER} is immutable). Every
 * mutation broadcasts a {@link RoleEvent} so connected clients refetch. A mask edit that changes the
 * permissions and a role deletion also re-enforce CONNECT on live voice sessions
 * ({@link VoiceAccessEnforcer}) — a role touches an arbitrary set of holders in an arbitrary set of
 * rooms, so those sweeps go everywhere.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    /** STOMP topic role changes are pushed to: {@code /topic/roles}. */
    public static final String ROLES_TOPIC = "/topic/roles";

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChannelRoleOverrideRepository channelRoleOverrideRepository;
    private final VoiceAccessEnforcer voiceEnforcer;

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        return roleRepository.findAllByOrderByPositionDesc().stream()
                .map(RoleResponse::from)
                .toList();
    }

    @Transactional
    public RoleResponse create(CreateRoleRequest request, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        auth.requireServer(actor, Permission.MANAGE_ROLES);
        validateRoleName(request.name());
        if (roleRepository.existsByName(request.name())) {
            throw conflict("Role name already taken");
        }
        long permissions = Permissions.fromNames(request.permissions());
        boolean mentionable = Boolean.TRUE.equals(request.mentionable());
        boolean hoist = Boolean.TRUE.equals(request.hoist());
        Role role = new Role(request.name(), nextCustomPosition(), permissions, mentionable, hoist, RoleKind.CUSTOM);
        role.setColor(request.color());
        role = roleRepository.save(role);
        publish("created", role.getId());
        return RoleResponse.from(role);
    }

    @Transactional
    public RoleResponse update(Long roleId, UpdateRoleRequest request, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        auth.requireServer(actor, Permission.MANAGE_ROLES);
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> notFound("Role not found"));
        if (role.getKind() == RoleKind.OWNER) {
            throw forbidden("The OWNER role cannot be edited");
        }
        if (role.getKind() == RoleKind.CUSTOM) {
            // cannot edit a role at or above your own level
            auth.requireCanAssignRole(actor, role);
            if (request.name() != null) {
                validateRoleName(request.name());
                if (!role.getName().equals(request.name()) && roleRepository.existsByName(request.name())) {
                    throw conflict("Role name already taken");
                }
                role.setName(request.name());
            }
            // colour is applied unconditionally (null = clear to the default/no colour). name above stays
            // partial-update (null = don't touch), but colour's null is meaningful: the role editor's
            // "Default Color" sends null to reset, so guarding it with != null would drop the reset.
            role.setColor(request.color());
            if (request.hoist() != null) {
                role.setHoist(request.hoist());
            }
        }
        // @everyone: only permissions (and mentionable) are editable, never name/color/hoist
        long permissionsBefore = role.getPermissions();
        if (request.permissions() != null) {
            role.setPermissions(Permissions.fromNames(request.permissions()));
        }
        if (request.mentionable() != null) {
            role.setMentionable(request.mentionable());
        }
        role = roleRepository.save(role);
        if (role.getPermissions() != permissionsBefore) {
            // Only a real mask change can evict anyone — a rename/colour/hoist-only edit rechecks nothing.
            voiceEnforcer.enforceEverywhere(actorUsername);
        }
        publish("updated", role.getId());
        return RoleResponse.from(role);
    }

    @Transactional
    public void delete(Long roleId, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        auth.requireServer(actor, Permission.MANAGE_ROLES);
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> notFound("Role not found"));
        if (role.getKind() != RoleKind.CUSTOM) {
            throw forbidden("Built-in roles cannot be deleted");
        }
        auth.requireCanAssignRole(actor, role);
        // user_roles has no ON DELETE CASCADE — clear assignments first
        for (User holder : userRepository.findUsersByRoleId(roleId)) {
            holder.getRoles().removeIf(assigned -> roleId.equals(assigned.getId()));
            userRepository.save(holder);
        }
        // channel_role_overrides.role_id has no ON DELETE CASCADE either — load + deleteAll (not a bulk
        // JPQL delete: that would leave stale managed overrides pointing at the role). Flush so the
        // override DELETEs reach the DB before the role DELETE, satisfying the FK.
        channelRoleOverrideRepository.deleteAll(channelRoleOverrideRepository.findByRole_Id(roleId));
        channelRoleOverrideRepository.flush();
        roleRepository.delete(role);
        // Holders lost the role (and the channel overrides died with it) — recheck every occupied room.
        voiceEnforcer.enforceEverywhere(actorUsername);
        publish("deleted", roleId);
    }

    @Transactional
    public void reorder(ReorderRolesRequest request, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        auth.requireServer(actor, Permission.MANAGE_ROLES);
        List<Role> ordered = new ArrayList<>();
        for (Long roleId : request.roleIds()) {
            Role role = roleRepository.findById(roleId)
                    .orElseThrow(() -> notFound("Role not found: " + roleId));
            if (role.getKind() != RoleKind.CUSTOM) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Only custom roles can be reordered");
            }
            ordered.add(role);
        }
        int position = ordered.size(); // first listed = highest position
        for (Role role : ordered) {
            role.setPosition(position);
            position--;
        }
        roleRepository.saveAll(ordered);
        publish("reordered", null);
    }

    /** Custom role names allow letters of ANY alphabet (user decision, 2026-09-11: banning
     *  cyrillic was wrong), plus digits, underscores and hyphens — banned are only the
     *  characters that would break text layout or the plain-text mention token (spaces,
     *  punctuation). NOTE: the {@code @token} mention parser still matches ASCII word
     *  characters only, so a non-latin name cannot be @-mentioned (it renders as plain
     *  text — a documented tradeoff, not a failure). Built-in {@code @everyone}/
     *  {@code OWNER} are seeded, never created/renamed here. */
    private static final java.util.regex.Pattern ROLE_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[\\p{L}\\p{N}_-]{1,32}$");

    private static void validateRoleName(String name) {
        if (name == null || !ROLE_NAME_PATTERN.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Role name must be 1-32 letters (any alphabet), digits, underscores or hyphens (no spaces)");
        }
    }

    /** Highest custom position + 1 (customs live far below OWNER_POSITION, so no collision). */
    private int nextCustomPosition() {
        List<Role> customs = roleRepository.findByKindOrderByPositionDesc(RoleKind.CUSTOM);
        return customs.isEmpty() ? 1 : customs.get(0).getPosition() + 1;
    }

    private void publish(String type, Long roleId) {
        messagingTemplate.convertAndSend(ROLES_TOPIC, new RoleEvent(type, roleId));
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
}
