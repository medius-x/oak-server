package oak.server.dto;

import java.util.Set;

/**
 * The caller's effective server-wide permissions (names) and OWNER flag — the client gates its UI
 * from this (e.g. hide the channel "+" without {@code MANAGE_CHANNELS}). Channel-scoped permissions
 * (overrides) are not included; the client gates those best-effort and the server is the authority.
 *
 * <p>{@code dmRestricted} (appended 3rd — wire append-only, paired client deploy) mirrors the
 * subtractive {@code RESTRICT_PRIVATE_MESSAGES} role bit: it is deliberately NOT part of
 * {@code permissions} — that list is a grant list (an ADMINISTRATOR's mask is ALL and would carry
 * the bit without any restricting role), while the flag is computed from the raw role masks.
 */
public record MyPermissions(Set<String> permissions, boolean owner, boolean dmRestricted) {
}
