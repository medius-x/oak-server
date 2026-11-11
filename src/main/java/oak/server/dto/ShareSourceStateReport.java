package oak.server.dto;

/**
 * The wire body of {@code SEND /app/share.source-state}: just the flip — the
 * username is stamped server-side from the principal on the relayed
 * {@link ShareSourceStateEvent}.
 */
public record ShareSourceStateReport(Boolean iconic) {
}
