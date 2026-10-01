package oak.server.dto;

/**
 * The live push on {@code /user/queue/ownership} (both parties, RelationshipEvent style):
 * {@code OFFERED} (a new pending offer), {@code ACCEPTED}/{@code DECLINED} (the recipient
 * decided), {@code CANCELLED} (the owner withdrew or the offer was replaced). The client's
 * reaction to every type is the same refetch of the pending snapshot.
 */
public record OwnershipEvent(String type, OwnershipTransferResponse transfer) {

    public static OwnershipEvent of(String type, OwnershipTransferResponse transfer) {
        return new OwnershipEvent(type, transfer);
    }
}
