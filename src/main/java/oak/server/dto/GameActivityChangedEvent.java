package oak.server.dto;

/**
 * Spring event fired by {@code PresenceActivityTracker} whenever a user's
 * DERIVED activity line actually moved (a new line, a replacement, a clear) —
 * including transitions the presence dampening swallows for broadcasting: the
 * game-session bookkeeping (GameSessionService) must see every real change.
 * {@code activity} is the new derived line; null means "no line" (everything
 * closes).
 */
public record GameActivityChangedEvent(String username, PresenceActivity activity) {
}
