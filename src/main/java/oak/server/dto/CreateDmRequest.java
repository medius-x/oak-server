package oak.server.dto;

import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/channels/dm} — start (or reopen) a 1:1 DM with a user by id. */
public record CreateDmRequest(@NotNull Long recipientId) {
}
