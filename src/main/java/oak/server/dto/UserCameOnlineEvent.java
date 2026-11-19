package oak.server.dto;

/**
 * Spring event fired by {@code PresenceTracker} when a user actually SHOWS UP
 * online — the first STOMP connect of their current session set (a second
 * concurrent client of the same user is not a new visit). The INVISIBLE mask
 * does not suppress it: it hides the user from the world, not the fact of the
 * visit. Consumed by {@code LastSeenRecorder} (the achievements' "was here"
 * data, V22).
 */
public record UserCameOnlineEvent(String username) {
}
