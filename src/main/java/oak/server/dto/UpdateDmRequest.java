package oak.server.dto;

import jakarta.validation.constraints.Size;

/** Body of {@code PATCH /api/channels/{id}} — rename a group DM (any participant). */
public record UpdateDmRequest(@Size(max = 100) String name) {
}
