package oak.server.dto;

/**
 * Binary connection state pushed over {@code /topic/presence}. Intentionally minimal;
 * idle/dnd/away are a later extension point.
 */
public enum PresenceStatus {
    ONLINE,
    OFFLINE
}
