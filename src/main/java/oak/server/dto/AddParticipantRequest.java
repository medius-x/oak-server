package oak.server.dto;

import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/channels/{id}/participants} — add a user to a group DM (owner-only). */
public record AddParticipantRequest(@NotNull Long userId) {
}
