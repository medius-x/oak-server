package oak.server.dto;

import java.util.List;

/**
 * The pull side of the status dimension ({@code GET /api/presence/status}, the
 * status menu round 2026-11-17): the usernames whose clients reported
 * {@code away} and {@code dnd}, as two plain sets — everyone absent from both
 * is {@code online} (the wire default) and {@code offline} stays the online-set
 * dimension's own story, never a hand-settable status.
 */
public record PresenceStatusSnapshot(List<String> away, List<String> dnd) {
}
