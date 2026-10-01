package oak.server.dto;

import oak.server.entity.OwnershipTransfer;

import java.time.Instant;

/**
 * A transfer offer on the wire: both parties' ids/logins/display names, the lifecycle
 * status and the creation stamp. Backs the pending snapshot ({@code GET
 * /api/ownership/pending}) and every {@link OwnershipEvent} push.
 */
public record OwnershipTransferResponse(Long id, Long fromUserId, String fromUsername, String fromDisplayName,
                                        Long toUserId, String toUsername, String toDisplayName,
                                        String status, Instant createdAt) {

    public static OwnershipTransferResponse from(OwnershipTransfer transfer) {
        return new OwnershipTransferResponse(
                transfer.getId(),
                transfer.getFromUser().getId(), transfer.getFromUser().getUsername(),
                transfer.getFromUser().displayName(),
                transfer.getToUser().getId(), transfer.getToUser().getUsername(),
                transfer.getToUser().displayName(),
                transfer.getStatus().name(), transfer.getCreatedAt());
    }
}
