package oak.server.service;

import lombok.RequiredArgsConstructor;
import oak.server.dto.RelationshipEvent;
import oak.server.dto.RelationshipResponse;
import oak.server.entity.Relationship;
import oak.server.entity.RelationshipType;
import oak.server.entity.User;
import oak.server.repository.RelationshipRepository;
import oak.server.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The social graph: friend requests, friendships, and blocks. Single directed {@link Relationship}
 * rows ({@code unique(source, target)}); a friendship is stored mutually (two rows) once accepted.
 *
 * <p><b>Events:</b> each mutation publishes a {@link RelationshipEvent} to the affected user(s) on
 * {@code /user/queue/relationships} via {@code convertAndSendToUser(username, ...)} — the username is
 * the STOMP principal name, so per-user delivery works exactly like member-kick / call-signal.
 *
 * <p><b>Mutual-request auto-accept:</b> if A requests B while B already has a pending request to A,
 * A's call immediately promotes both to friends (Discord behavior) instead of creating a redundant
 * pending edge.
 *
 * <p><b>Why upsert, not delete+insert:</b> the {@code (source_id, target_id)} unique constraint means a
 * pending edge and a friendship can't coexist on the same ordered pair. Flipping PENDING&rarr;FRIEND in
 * place (an {@code UPDATE}) avoids Hibernate's insert-before-delete action-queue ordering, which would
 * otherwise fire a unique-constraint violation when the new FRIEND row is inserted before the old
 * PENDING row is deleted.
 */
@Service
@RequiredArgsConstructor
public class RelationshipService {

    /** Per-user queue relationship deltas are pushed to (clients subscribe to {@code /user/queue/relationships}). */
    public static final String RELATIONSHIPS_QUEUE = "/queue/relationships";

    private final RelationshipRepository relationshipRepository;
    private final UserRepository userRepository;
    private final AuthorizationService auth;
    private final AttachmentUrlSigner signer;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional(readOnly = true)
    public List<RelationshipResponse> listMine(String username) {
        User actor = auth.requireUser(username);
        List<RelationshipResponse> result = new ArrayList<>();
        for (Relationship relationship : relationshipRepository.findBySourceOrderByCreatedAtDesc(actor)) {
            RelationshipResponse.Type view = switch (relationship.getType()) {
                case FRIEND -> RelationshipResponse.Type.FRIEND;
                case BLOCKED -> RelationshipResponse.Type.BLOCKED;
                case PENDING -> RelationshipResponse.Type.PENDING_OUTGOING;
            };
            result.add(RelationshipResponse.of(relationship.getTarget(), view, signer));
        }
        // Incoming pending requests: I am the target — the other party is the source.
        for (Relationship relationship : relationshipRepository.findByTargetAndType(actor, RelationshipType.PENDING)) {
            result.add(RelationshipResponse.of(relationship.getSource(), RelationshipResponse.Type.PENDING_INCOMING, signer));
        }
        return result;
    }

    @Transactional
    public RelationshipResponse sendRequest(String username, String targetUsername) {
        if (username.equals(targetUsername)) {
            throw badRequest("You cannot add yourself as a friend");
        }
        User actor = auth.requireUser(username);
        User target = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> notFound("User not found"));
        if (relationshipRepository.blockedEitherDirection(actor, target)) {
            throw forbidden("You cannot add a blocked user as a friend");
        }
        // Mutual-request → auto-accept: their pending to us becomes a friendship immediately.
        if (relationshipRepository.findBySourceAndTargetAndType(target, actor, RelationshipType.PENDING).isPresent()) {
            befriend(actor, target);
            notify(target, "FRIEND_ACCEPTED", actor);
            notify(actor, "FRIEND_ACCEPTED", target);
            return RelationshipResponse.of(target, RelationshipResponse.Type.FRIEND, signer);
        }
        if (friendExists(actor, target)) {
            throw conflict("You are already friends");
        }
        if (relationshipRepository.findBySourceAndTargetAndType(actor, target, RelationshipType.PENDING).isPresent()) {
            throw conflict("Friend request already sent");
        }
        relationshipRepository.save(new Relationship(actor, target, RelationshipType.PENDING));
        notify(target, "FRIEND_REQUEST", actor);
        return RelationshipResponse.of(target, RelationshipResponse.Type.PENDING_OUTGOING, signer);
    }

    @Transactional
    public RelationshipResponse accept(String username, Long requesterId) {
        User actor = auth.requireUser(username);
        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> notFound("User not found"));
        // A pending request must exist from requester — accept is not a way to force a friendship.
        relationshipRepository.findBySourceAndTargetAndType(requester, actor, RelationshipType.PENDING)
                .orElseThrow(() -> notFound("No pending friend request from that user"));
        befriend(actor, requester);
        notify(requester, "FRIEND_ACCEPTED", actor);
        notify(actor, "FRIEND_ACCEPTED", requester);
        return RelationshipResponse.of(requester, RelationshipResponse.Type.FRIEND, signer);
    }

    /** Cancel an outgoing request, decline an incoming request, or unfriend — all idempotent. */
    @Transactional
    public void removeRelationship(String username, Long targetUserId) {
        User actor = auth.requireUser(username);
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> notFound("User not found"));
        boolean wasFriend = friendExists(actor, target);
        relationshipRepository.deleteBySourceAndTargetAndType(actor, target, RelationshipType.PENDING);
        relationshipRepository.deleteBySourceAndTargetAndType(target, actor, RelationshipType.PENDING);
        relationshipRepository.deleteBySourceAndTargetAndType(actor, target, RelationshipType.FRIEND);
        relationshipRepository.deleteBySourceAndTargetAndType(target, actor, RelationshipType.FRIEND);
        if (wasFriend) {
            notify(target, "FRIEND_REMOVED", actor);
        }
    }

    @Transactional
    public RelationshipResponse block(String username, String targetUsername) {
        if (username.equals(targetUsername)) {
            throw badRequest("You cannot block yourself");
        }
        User actor = auth.requireUser(username);
        User target = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> notFound("User not found"));
        // Drop any reverse-direction friendship / pending (target → actor); a block is one-way but must
        // end any standing friendship. These are deletes on a different key than the block below.
        relationshipRepository.deleteBySourceAndTargetAndType(target, actor, RelationshipType.PENDING);
        relationshipRepository.deleteBySourceAndTargetAndType(target, actor, RelationshipType.FRIEND);
        // Replace the forward edge (actor → target) with BLOCKED in place when one exists, so we never
        // insert a BLOCKED row on a key a just-deleted row still occupies (insert-before-delete ordering).
        Optional<Relationship> forward = relationshipRepository.findBySourceAndTargetAndType(actor, target, RelationshipType.PENDING)
                .or(() -> relationshipRepository.findBySourceAndTargetAndType(actor, target, RelationshipType.FRIEND));
        boolean alreadyBlocked = relationshipRepository.findBySourceAndTargetAndType(
                actor, target, RelationshipType.BLOCKED).isPresent();
        if (!alreadyBlocked) {
            if (forward.isPresent()) {
                forward.get().setType(RelationshipType.BLOCKED);
                relationshipRepository.save(forward.get());
            } else {
                relationshipRepository.save(new Relationship(actor, target, RelationshipType.BLOCKED));
            }
            notify(target, "BLOCKED", actor);
        }
        return RelationshipResponse.of(target, RelationshipResponse.Type.BLOCKED, signer);
    }

    @Transactional
    public void unblock(String username, Long targetUserId) {
        User actor = auth.requireUser(username);
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> notFound("User not found"));
        relationshipRepository.deleteBySourceAndTargetAndType(actor, target, RelationshipType.BLOCKED);
        notify(target, "UNBLOCKED", actor);
    }

    /**
     * Establishes mutual friendship. For each direction, flips an existing PENDING edge to FRIEND in
     * place, or inserts a FRIEND edge when none exists (idempotent if already FRIEND). Never deletes,
     * so no insert-after-delete unique-constraint race.
     */
    private void befriend(User actor, User target) {
        upsertFriendEdge(actor, target);
        upsertFriendEdge(target, actor);
    }

    private void upsertFriendEdge(User source, User target) {
        Optional<Relationship> pending = relationshipRepository.findBySourceAndTargetAndType(
                source, target, RelationshipType.PENDING);
        if (pending.isPresent()) {
            pending.get().setType(RelationshipType.FRIEND);
            relationshipRepository.save(pending.get());
            return;
        }
        if (relationshipRepository.findBySourceAndTargetAndType(source, target, RelationshipType.FRIEND).isPresent()) {
            return;   // already friends on this edge — idempotent
        }
        relationshipRepository.save(new Relationship(source, target, RelationshipType.FRIEND));
    }

    private boolean friendExists(User actor, User target) {
        return relationshipRepository.findBySourceAndTargetAndType(actor, target, RelationshipType.FRIEND).isPresent()
                || relationshipRepository.findBySourceAndTargetAndType(target, actor, RelationshipType.FRIEND).isPresent();
    }

    private void notify(User recipient, String type, User other) {
        messagingTemplate.convertAndSendToUser(recipient.getUsername(), RELATIONSHIPS_QUEUE,
                new RelationshipEvent(type, other.getId(), other.getUsername()));
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    private static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
