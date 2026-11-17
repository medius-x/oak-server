package oak.server.dto;

/**
 * A roster-presence STATUS flip (the status menu, user request 2026-11-17):
 * {@code status} is one of {@code online}, {@code away} (idle at the computer,
 * the client's own threshold) or {@code dnd} (a manual pick, or the auto-mode's
 * verdict while the user sits in a voice room or a 1:1 call — the deciding is
 * client-side). Broadcast on {@code /topic/roster.status}; the pull side
 * ({@code GET /api/presence/status}) serves the non-online set. {@code offline}
 * is deliberately NOT a status: it is what {@code /topic/presence} tells when
 * every session of the user is gone, and it can never be reported by hand.
 */
public record PresenceStatusEvent(String username, String status) {
}
