package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.AssignRolesRequest;
import oak.server.dto.MemberEvent;
import oak.server.dto.MemberResponse;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.websocket.VoiceAccessEnforcer;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Member management: list members with roles, assign roles, kick, ban / unban. Single-tenant means
 * there is no membership entity to revoke, so "kick" is cooperative — it pushes a
 * {@code /queue/member.kick} command to the member's client to disconnect (and {@code banned=true}
 * when banning). "Ban" flags the account ({@link User#isBanned()}) so the member cannot log in or
 * open a STOMP connection again. All mutations broadcast a {@link MemberEvent}. Replacing a
 * member's roles also re-enforces CONNECT on that member's live voice sessions
 * ({@link VoiceAccessEnforcer}) — only their effective mask changed, so only their rooms recheck.
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    /** STOMP topic member changes are pushed to: {@code /topic/members}. */
    public static final String MEMBERS_TOPIC = "/topic/members";
    /** Per-member queue a kick/ban command is pushed to (client disconnects on receipt). */
    public static final String MEMBER_KICK_QUEUE = "/queue/member.kick";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuthorizationService auth;
    private final AttachmentUrlSigner signer;
    private final SimpMessagingTemplate messagingTemplate;
    private final VoiceAccessEnforcer voiceEnforcer;

    @Transactional(readOnly = true)
    public List<MemberResponse> list() {
        return userRepository.findAllByOrderByIdAsc().stream()
                .map(user -> MemberResponse.from(user, signer, auth.isOwner(user)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> banned() {
        return userRepository.findAllByOrderByIdAsc().stream()
                .filter(User::isBanned)
                .map(user -> MemberResponse.from(user, signer, auth.isOwner(user)))
                .toList();
    }

    /** Replaces a member's roles with exactly the given set; an existing OWNER role is always retained. */
    @Transactional
    public MemberResponse assignRoles(Long userId, AssignRolesRequest request, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found"));
        auth.requireServer(actor, Permission.MANAGE_MEMBERS);
        auth.requireCanActOn(actor, target);

        Set<Role> newRoles = new HashSet<>();
        for (Long roleId : request.roleIds()) {
            Role role = roleRepository.findById(roleId)
                    .orElseThrow(() -> notFound("Role not found: " + roleId));
            if (role.getKind() == RoleKind.OWNER) {
                // OWNER can't be gained via this endpoint (only transferred). The client sends a
                // member's full role set, so an OWNER-holder's request always carries their OWNER id —
                // that's a keep (skip; it's re-added below). But OWNER in the request for a non-owner IS
                // an assign attempt — reject it, so the protection is explicit, not silent.
                if (!auth.isOwner(target)) {
                    throw forbidden("The OWNER role is transferred, not assigned");
                }
                continue;
            }
            auth.requireCanAssignRole(actor, role);
            newRoles.add(role);
        }
        // keep OWNER if the target already holds it (a non-owner cannot strip it)
        if (target.getRoles() != null) {
            for (Role existing : target.getRoles()) {
                if (existing.getKind() == RoleKind.OWNER) {
                    newRoles.add(existing);
                }
            }
        }
        target.getRoles().clear();
        target.getRoles().addAll(newRoles);
        userRepository.save(target);
        // The member's effective mask may have just lost CONNECT — evict them from rooms they no
        // longer belong in (same in-transaction visibility as the override hooks; see VoiceAccessEnforcer).
        voiceEnforcer.enforceConnectForUser(target.getUsername(), actorUsername);
        publish("rolesChanged", target.getId());
        return MemberResponse.from(target, signer, auth.isOwner(target));
    }

    @Transactional
    public void kick(Long userId, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found"));
        auth.requireServer(actor, Permission.KICK_MEMBERS);
        auth.requireCanActOn(actor, target);
        messagingTemplate.convertAndSendToUser(target.getUsername(), MEMBER_KICK_QUEUE,
                Map.of("by", actor.getUsername(), "banned", false));
        publish("kicked", target.getId());
    }

    @Transactional
    public void ban(Long userId, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found"));
        auth.requireServer(actor, Permission.BAN_MEMBERS);
        auth.requireCanActOn(actor, target);
        if (auth.isOwner(target)) {
            throw forbidden("The owner cannot be banned");
        }
        target.setBanned(true);
        userRepository.save(target);
        messagingTemplate.convertAndSendToUser(target.getUsername(), MEMBER_KICK_QUEUE,
                Map.of("by", actor.getUsername(), "banned", true));
        publish("banned", target.getId());
    }

    @Transactional
    public void unban(Long userId, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        auth.requireServer(actor, Permission.BAN_MEMBERS);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found"));
        target.setBanned(false);
        userRepository.save(target);
        publish("rolesChanged", target.getId());
    }

    private void publish(String type, Long userId) {
        messagingTemplate.convertAndSend(MEMBERS_TOPIC, new MemberEvent(type, userId));
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
}
