package oak.server.dto;

/**
 * A roster activity line ("playing CS2", "listening to radio"): the user-set status
 * that renders under the nickname in the members pane. {@code type} is the verb
 * (the client localizes the prefix), {@code name} is the free-text object.
 * {@code igdbGameId} (3rd, 2026-11-19, the IGDB round) is the optional card
 * reference of an auto-detected GAME line — the rendering clients fetch the card
 * (cover/genres) from the /api/games cache by it. Absent/null for every manual
 * line; transit state like presence itself: the server only relays and snapshots
 * it, nothing is persisted.
 */
public record PresenceActivity(String type, String name, Long igdbGameId) {

    /** The manual-line constructor (everything before the IGDB round). */
    public PresenceActivity(String type, String name) {
        this(type, name, null);
    }
}
