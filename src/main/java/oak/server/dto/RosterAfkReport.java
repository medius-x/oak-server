package oak.server.dto;

/**
 * Body of {@code /app/roster.afk}: the CLIENT'S verdict about its own user — {@code true}
 * when the member has been idle at the computer for the roster threshold (system-wide
 * input, no speaking, no outgoing stream), {@code false} the moment they are back. The
 * username is never taken from the body: the server stamps it from the authenticated
 * principal, so a report can only ever describe its own sender.
 */
public record RosterAfkReport(Boolean afk) {
}
