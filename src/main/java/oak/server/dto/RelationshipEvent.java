package oak.server.dto;

/**
 * Pushed to {@code /user/queue/relationships} to tell a user their social graph changed (a request
 * arrived, a request was accepted, a friend was removed, they were blocked/unblocked). {@code userId}
 * and {@code username} identify the <em>other</em> party from the recipient's point of view.
 */
public record RelationshipEvent(String type, Long userId, String username) {
}
