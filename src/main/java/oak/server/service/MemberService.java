package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.AssignRolesRequest;
import oak.server.dto.MemberEvent;
import oak.server.dto.MemberResponse;
import oak.server.dto.RenameMemberRequest;
import oak.server.dto.UserSummary;
import oak.server.controller.UserController;
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
 * Member management: list members with roles, assign roles, remove, ban / unban. "Remove from
 * server" (the reworked kick) is PERMANENT: the account is flagged {@link User#isRemoved()},
 * anonymized ({@code deleted_<id>} / {@code Deleted-User-<id>}, freeing the original login and
 * nickname for a fresh registration) and can never come back — enforced at the same four gates as
 * a ban (REST filter, login, refresh, STOMP CONNECT). Before the rename the victim's live
 * sessions are told to disconnect ({@code /queue/member.kick} with {@code removed=true}) and are
 * evicted from every voice room. "Ban" flags the account ({@link User#isBanned()}) so the member
 * cannot log in or open a STOMP connection again — but a ban is reversible (unban), a removal is
 * not. All mutations broadcast a {@link MemberEvent}. Replacing a member's roles also
 * re-enforces CONNECT on that member's live voice sessions ({@link VoiceAccessEnforcer}) — only
 * their effective mask changed, so only their rooms recheck.
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
    private final OwnershipTransferService ownershipTransferService;

    @Transactional(readOnly = true)
    public List<MemberResponse> list() {
        return userRepository.findAllByOrderByIdAsc().stream()
                .filter(user -> !user.isRemoved())
                .map(user -> MemberResponse.from(user, signer, auth.isOwner(user), auth.dmRestricted(user)))
                .toList();
    }

    /** The moderation audit list: banned accounts (unban-able) and removed ones (permanent) —
     *  {@link MemberResponse#removed()} tells the two apart. */
    @Transactional(readOnly = true)
    public List<MemberResponse> banned() {
        return userRepository.findAllByOrderByIdAsc().stream()
                .filter(user -> user.isBanned() || user.isRemoved())
                .map(user -> MemberResponse.from(user, signer, auth.isOwner(user), auth.dmRestricted(user)))
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
        return MemberResponse.from(target, signer, auth.isOwner(target), auth.dmRestricted(target));
    }

    /** Sets a member's NICKNAME — the display name every surface shows and the only name mentions
     *  resolve against. Renaming SOMEONE ELSE: {@code MANAGE_NICKNAMES} + hierarchy (strictly higher
     *  top position; an OWNER target is immune). Renaming YOURSELF: {@code CHANGE_NICKNAME}, no
     *  hierarchy gate (Discord semantics, mirroring the self-branch of voice mute). Uniqueness is
     *  checked against OTHER nicknames only — a nickname may equal someone's login (logins are never
     *  revealed: no 409 probe, no mention matching). Broadcasts {@code /topic/members} (refetch) and
     *  a live {@code /topic/users} update so every client repaints the name instantly. */
    @Transactional
    public MemberResponse rename(Long userId, RenameMemberRequest request, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found"));
        if (actor.getId() != null && actor.getId().equals(target.getId())) {
            auth.requireServer(actor, Permission.CHANGE_NICKNAME);
        } else {
            auth.requireServer(actor, Permission.MANAGE_NICKNAMES);
            auth.requireCanActOn(actor, target);
        }
        String nickname = request.nickname().trim();
        if (nickname.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nickname must not be blank");
        }
        if (userRepository.existsByNickname(nickname)
                && userRepository.findByNickname(nickname).map(User::getId).filter(nicknameOwnerId -> nicknameOwnerId.equals(target.getId())).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nickname is already taken");
        }
        target.setNickname(nickname);
        userRepository.save(target);
        publish("renamed", target.getId());
        messagingTemplate.convertAndSend(UserController.USERS_TOPIC, UserSummary.from(target, signer));
        return MemberResponse.from(target, signer, auth.isOwner(target), auth.dmRestricted(target));
    }

    /** Resets the nickname to the member's login handle (the fresh-account default). 409 if that
     *  name is already held as someone else's nickname. */
    @Transactional
    public MemberResponse resetNickname(Long userId, String actorUsername) {
        String login = userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found"))
                .getUsername();
        return rename(userId, new RenameMemberRequest(login), actorUsername);
    }

    /**
     * "Remove from server" — the reworked kick, permanent by design. Gated by KICK_MEMBERS (it is the
     * same admin surface the old kick lived behind; the irreversibility is communicated by the client's
     * confirm copy, not by a different permission bit) plus the hierarchy; the OWNER cannot be removed.
     *
     * <p>Order matters: the victim's live sessions still know the OLD username, so the disconnect push
     * and the voice eviction go out FIRST, and only then the account is flagged and anonymized
     * ({@code deleted_<id>} / {@code Deleted-User-<id>}, with a suffix retry should a tombstone name
     * collide). Anonymization frees the original login and nickname for a fresh registration while
     * every old message keeps its true author — the wire's {@code authorUsername} now resolves to the
     * tombstone row, so an impersonator registering under the freed name can never inherit the
     * authorship (their row is a different id). A {@code /topic/users} push repaints the tombstone
     * name everywhere (the rename machinery).
     */
    @Transactional
    public void remove(Long userId, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found"));
        auth.requireServer(actor, Permission.KICK_MEMBERS);
        auth.requireCanActOn(actor, target);
        if (auth.isOwner(target)) {
            throw forbidden("The owner cannot be removed");
        }
        // A pending ownership offer to or from the victim dies with them (inside the
        // same tx — a removed recipient could never accept).
        ownershipTransferService.cancelPendingFor(target);
        messagingTemplate.convertAndSendToUser(target.getUsername(), MEMBER_KICK_QUEUE,
                Map.of("by", actor.getUsername(), "banned", false, "removed", true));
        voiceEnforcer.evictEverywhere(target.getUsername(), actor.getUsername());
        target.setRemoved(true);
        // The tombstone names: unique by the user's own id, with an attempt suffix should someone
        // already sit on the exact tombstone (an exotic but possible registration).
        int attempt = 0;
        while (userRepository.existsByUsername(tombstoneLogin(target.getId(), attempt))
                || userRepository.existsByNickname(tombstoneNickname(target.getId(), attempt))) {
            attempt++;
        }
        target.setUsername(tombstoneLogin(target.getId(), attempt));
        target.setNickname(tombstoneNickname(target.getId(), attempt));
        userRepository.save(target);
        publish("removed", target.getId());
        messagingTemplate.convertAndSend(UserController.USERS_TOPIC, UserSummary.from(target, signer));
    }

    /** {@code deleted_<id>} / {@code deleted_<id>-2}, {@code -3}, … — the removed account's login tombstone. */
    private static String tombstoneLogin(Long userId, int attempt) {
        return "deleted_" + userId + (attempt == 0 ? "" : "-" + (attempt + 1));
    }

    /** {@code Deleted-User-<id>} — the display-name tombstone (the nickname grammar, no spaces). */
    private static String tombstoneNickname(Long userId, int attempt) {
        return "Deleted-User-" + userId + (attempt == 0 ? "" : "-" + (attempt + 1));
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
        if (target.isRemoved()) {
            throw badRequest("This member was removed, not banned");
        }
        // A banned recipient can never accept; a banned sender is no longer acting owner.
        ownershipTransferService.cancelPendingFor(target);
        target.setBanned(true);
        userRepository.save(target);
        messagingTemplate.convertAndSendToUser(target.getUsername(), MEMBER_KICK_QUEUE,
                Map.of("by", actor.getUsername(), "banned", true));
        // H8: a ban must reach the VOICE rooms too (the remove() twin): REST/STOMP cut off
        // instantly, but a rogue already holding a minted token+pin keeps publishing/listening
        // until the eviction revokes the token and kicks the gateway publishers.
        voiceEnforcer.evictEverywhere(target.getUsername(), actor.getUsername());
        publish("banned", target.getId());
    }

    @Transactional
    public void unban(Long userId, String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        auth.requireServer(actor, Permission.BAN_MEMBERS);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found"));
        if (target.isRemoved()) {
            throw badRequest("This member was removed from the server — removal is permanent");
        }
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

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
