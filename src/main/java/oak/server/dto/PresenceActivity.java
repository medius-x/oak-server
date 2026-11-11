package oak.server.dto;

/**
 * A roster activity line ("playing CS2", "listening to radio"): the user-set status
 * that renders under the nickname in the members pane. {@code type} is the verb
 * (the client localizes the prefix), {@code name} is the free-text object.
 * Transit state like presence itself: the server only relays and snapshots it,
 * nothing is persisted.
 */
public record PresenceActivity(String type, String name) {
}
