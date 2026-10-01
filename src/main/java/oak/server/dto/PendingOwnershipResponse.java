package oak.server.dto;

/**
 * The pending-offer snapshot: always a JSON body ({@code {"pending": null}} when the
 * caller is not a party to the live offer), so the client never parses an empty body.
 */
public record PendingOwnershipResponse(OwnershipTransferResponse pending) {

    public static PendingOwnershipResponse of(OwnershipTransferResponse pending) {
        return new PendingOwnershipResponse(pending);
    }
}
