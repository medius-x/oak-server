package oak.server.dto;

/**
 * {@code SEND /app/share.source-state} → {@code /topic/share.state} (2026-11-11):
 * a publisher's share-source flip (minimized ↔ restored). A MINIMIZED shared
 * window does not die — Windows Graphics Capture freezes its last frame and the
 * viewers stare at a still picture; the publisher's client detects the flip and
 * this relay lets the viewers' pop-out caption the frozen frame honestly.
 *
 * <p>{@code username} is stamped SERVER-side from the authenticated principal;
 * the wire report carries only the boolean.
 */
public record ShareSourceStateEvent(String username, boolean iconic) {
}
