package oak.server.entity;

/**
 * Directed relationship between two users, stored as a string on {@link Relationship} (see
 * {@code @Enumerated(STRING)}). {@code PENDING} and {@code BLOCKED} are directed by nature
 * (source requested/blocked target); {@code FRIEND} is stored mutually — two rows, one each
 * direction — once a request is accepted, so "my friends" is a single range scan on
 * {@code source = me}.
 */
public enum RelationshipType {
    /** A friend request has been sent (source &rarr; target) and is awaiting acceptance. */
    PENDING,
    /** An established friendship. Stored as two rows (mutual) once accepted. */
    FRIEND,
    /** source has blocked target. */
    BLOCKED
}
