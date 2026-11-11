package oak.server.dto;

/**
 * Body of {@code SEND /app/presence.activity}: the activity to set, or {@code null}
 * to clear. The principal is stamped server-side — a report can only describe its
 * own sender (the RosterAfkReport discipline).
 */
public record PresenceActivityReport(PresenceActivity activity) {
}
