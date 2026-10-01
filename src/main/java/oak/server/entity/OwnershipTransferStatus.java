package oak.server.entity;

/** The lifecycle of a server-ownership transfer offer (single-tenant: at most one PENDING exists). */
public enum OwnershipTransferStatus {
    /** Offered by the owner, waiting for the recipient's accept or decline. */
    PENDING,
    /** The recipient accepted — the OWNER role moved to them. */
    ACCEPTED,
    /** The recipient declined. */
    DECLINED,
    /** The owner withdrew the offer (or replaced it with one to someone else, or the
     *  recipient was banned/removed while the offer was pending). */
    CANCELLED
}
