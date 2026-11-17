package oak.server.dto;

/**
 * Body of {@code /app/roster.status}: the CLIENT'S verdict about its own user —
 * {@code status} is {@code online}, {@code away} or {@code dnd} (the manual pick or
 * the auto-mode's outcome; the auto/manual distinction itself never leaves the
 * client), and {@code invisible} (null = false) asks the server to show the user as
 * OFFLINE to everyone while the sessions stay alive. The username is never taken
 * from the body: the server stamps it from the authenticated principal, so a report
 * can only ever describe its own sender.
 */
public record RosterStatusReport(String status, Boolean invisible) {
}
