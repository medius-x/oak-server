package oak.server.dto;

import java.util.Set;

/**
 * The caller's effective server-wide permissions (names) and OWNER flag — the client gates its UI
 * from this (e.g. hide the channel "+" without {@code MANAGE_CHANNELS}). Channel-scoped permissions
 * (overrides) are not included; the client gates those best-effort and the server is the authority.
 */
public record MyPermissions(Set<String> permissions, boolean owner) {
}
