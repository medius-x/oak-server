package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.MemberEvent;
import oak.server.dto.OwnershipEvent;
import oak.server.dto.OwnershipTransferResponse;
import oak.server.dto.PendingOwnershipResponse;
import oak.server.entity.OwnershipTransfer;
import oak.server.entity.OwnershipTransferStatus;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.OwnershipTransferRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Server-ownership transfer, Discord-style: the owner OFFERS, the recipient ACCEPTS or
 * DECLINES, and only the accept moves the built-in OWNER role (it can never be assigned
 * through the role endpoints — {@code MemberService.assignRoles} rejects that). The
 * previous owner keeps every other role; the transfer back can only be initiated by the
 * NEW owner. A PENDING offer persists across restarts (the recipient learns about it from
 * the REST snapshot at session start), at most one exists at a time — a new offer silently
 * cancels the previous one — and it is cancelled when either party is banned or removed.
 *
 * <p>The accept publishes {@code rolesChanged} member events for BOTH users, so every
 * client refetches {@code GET /api/members} and its own permission mask — the crowns and
 * the Server-menu entry flip live with no transfer-specific broadcast machinery.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OwnershipTransferService {

    /** Per-user queue the transfer lifecycle is pushed to (both parties). The BARE
     *  {@code /queue/...} form on purpose — {@code convertAndSendToUser} prefixes
     *  {@code /user} itself, and the client subscribes to {@code /user/queue/ownership}
     *  (the /queue/member.kick and /queue/relationships convention). */
    public static final String OWNERSHIP_QUEUE = "/queue/ownership";

    private final OwnershipTransferRepository transferRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;

    /** The owner offers the server to a member. Replaces any live PENDING offer (both
     *  parties of the old one are notified with a CANCELLED push). */
    @Transactional
    public OwnershipTransferResponse offer(String actorUsername, Long targetUserId) {
        User actor = auth.requireUser(actorUsername);
        if (!auth.isOwner(actor)) {
            throw forbidden("Only the owner can transfer ownership");
        }
        if (actor.getId().equals(targetUserId)) {
            throw badRequest("You already hold the owner role");
        }
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> notFound("User not found"));
        if (target.isBanned() || target.isRemoved()) {
            throw badRequest("That member cannot receive ownership");
        }
        cancelPending("replaced", Optional.empty());
        OwnershipTransfer transfer = transferRepository.save(new OwnershipTransfer(actor, target, OwnershipTransferStatus.PENDING));
        OwnershipTransferResponse response = OwnershipTransferResponse.from(transfer);
        pushAfterCommit(() -> push("OFFERED", response));
        return response;
    }

    /** The recipient accepts: the OWNER role moves from the offer's sender to them.
     *  409 if the sender is no longer the owner (a stale offer must not steal the crown). */
    @Transactional
    public OwnershipTransferResponse accept(String actorUsername, Long transferId) {
        User actor = auth.requireUser(actorUsername);
        OwnershipTransfer transfer = requirePending(transferId);
        if (!transfer.getToUser().getId().equals(actor.getId())) {
            throw forbidden("Only the recipient can accept");
        }
        if (!auth.isOwner(transfer.getFromUser())) {
            throw conflict("The sender is no longer the owner");
        }
        Role ownerRole = roleRepository.findByKind(RoleKind.OWNER)
                .orElseThrow(() -> new IllegalStateException("The OWNER role is not seeded"));
        User from = transfer.getFromUser();
        from.getRoles().remove(ownerRole);
        userRepository.save(from);
        actor.getRoles().add(ownerRole);
        userRepository.save(actor);
        transfer.setStatus(OwnershipTransferStatus.ACCEPTED);
        transferRepository.save(transfer);
        OwnershipTransferResponse response = OwnershipTransferResponse.from(transfer);
        log.info("[ownership] transferred from={} to={}", from.getUsername(), actor.getUsername());
        // Both members' role sets changed: every client refetches the roster and its own
        // gate (crowns, the Server-menu entry, permission masks). AFTER COMMIT — an
        // in-tx push lets subscribers refetch before the swap is visible and every
        // crown would stay where it was (the invites lesson, live-reported here).
        pushAfterCommit(() -> {
            messagingTemplate.convertAndSend(MemberService.MEMBERS_TOPIC, new MemberEvent("rolesChanged", from.getId()));
            messagingTemplate.convertAndSend(MemberService.MEMBERS_TOPIC, new MemberEvent("rolesChanged", actor.getId()));
            push("ACCEPTED", response);
        });
        return response;
    }

    /** The recipient declines. */
    @Transactional
    public void decline(String actorUsername, Long transferId) {
        User actor = auth.requireUser(actorUsername);
        OwnershipTransfer transfer = requirePending(transferId);
        if (!transfer.getToUser().getId().equals(actor.getId())) {
            throw forbidden("Only the recipient can decline");
        }
        settle(transfer, OwnershipTransferStatus.DECLINED, "DECLINED");
    }

    /** The sender withdraws their pending offer. */
    @Transactional
    public void cancel(String actorUsername, Long transferId) {
        User actor = auth.requireUser(actorUsername);
        OwnershipTransfer transfer = requirePending(transferId);
        if (!transfer.getFromUser().getId().equals(actor.getId())) {
            throw forbidden("Only the sender can cancel");
        }
        settle(transfer, OwnershipTransferStatus.CANCELLED, "CANCELLED");
    }

    /** The live PENDING offer as seen by this member — null unless they are the sender
     *  or the recipient. Backs the session-start snapshot (a pending offer must survive
     *  the recipient being offline). */
    @Transactional(readOnly = true)
    public PendingOwnershipResponse pending(String actorUsername) {
        User actor = auth.requireUser(actorUsername);
        return transferRepository.findFirstByStatusOrderByCreatedAtAsc(OwnershipTransferStatus.PENDING)
                .filter(transfer -> transfer.getFromUser().getId().equals(actor.getId())
                        || transfer.getToUser().getId().equals(actor.getId()))
                .map(transfer -> PendingOwnershipResponse.of(OwnershipTransferResponse.from(transfer)))
                .orElseGet(() -> PendingOwnershipResponse.of(null));
    }

    /** Cancels the live PENDING offer when either party is banned or removed (the
     *  member hooks): a banned recipient could never accept, a removed/banned sender
     *  is no longer the owner. No-op when nothing is pending. */
    @Transactional
    public void cancelPendingFor(User affected) {
        cancelPending("member-left", Optional.of(affected));
    }

    /** Marks the live PENDING row CANCELLED and notifies both parties (or just the
     *  un-affected one when the cause IS one of the parties — they get their own
     *  kick/ban push already, but the banner must disappear for the OTHER side too). */
    private void cancelPending(String reason, Optional<User> affected) {
        transferRepository.findFirstByStatusOrderByCreatedAtAsc(OwnershipTransferStatus.PENDING)
                .ifPresent(pending -> {
                    pending.setStatus(OwnershipTransferStatus.CANCELLED);
                    transferRepository.save(pending);
                    OwnershipTransferResponse response = OwnershipTransferResponse.from(pending);
                    log.info("[ownership] pending offer {} cancelled ({})", pending.getId(), reason);
                    pushAfterCommit(() -> {
                        if (affected.isEmpty()) {
                            push("CANCELLED", response);
                            return;
                        }
                        // Notify the party the change did NOT happen to — the affected one
                        // learns everything from the ban/remove flow itself.
                        if (response.fromUserId().equals(affected.get().getId())) {
                            messagingTemplate.convertAndSendToUser(response.toUsername(), OWNERSHIP_QUEUE,
                                    OwnershipEvent.of("CANCELLED", response));
                        } else {
                            messagingTemplate.convertAndSendToUser(response.fromUsername(), OWNERSHIP_QUEUE,
                                    OwnershipEvent.of("CANCELLED", response));
                        }
                    });
                });
    }

    private void settle(OwnershipTransfer transfer, OwnershipTransferStatus status, String eventType) {
        transfer.setStatus(status);
        transferRepository.save(transfer);
        OwnershipTransferResponse response = OwnershipTransferResponse.from(transfer);
        pushAfterCommit(() -> push(eventType, response));
    }

    /** Runs the sends AFTER the surrounding tx commits — an in-tx push lets subscribers
     *  refetch before the mutation is visible (the invites lesson). No active
     *  synchronization (a no-tx caller) sends directly. */
    private static void pushAfterCommit(Runnable send) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send.run();
            }
        });
    }

    private OwnershipTransfer requirePending(Long transferId) {
        OwnershipTransfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> notFound("Unknown transfer"));
        if (transfer.getStatus() != OwnershipTransferStatus.PENDING) {
            throw conflict("This transfer is no longer pending");
        }
        return transfer;
    }

    private void push(String type, OwnershipTransferResponse response) {
        messagingTemplate.convertAndSendToUser(response.fromUsername(), OWNERSHIP_QUEUE,
                OwnershipEvent.of(type, response));
        messagingTemplate.convertAndSendToUser(response.toUsername(), OWNERSHIP_QUEUE,
                OwnershipEvent.of(type, response));
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
