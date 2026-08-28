package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/channels/{id}/name} — rename a TEXT/VOICE channel ({@code MANAGE_CHANNELS};
 * DM channels are rejected by the service, their rename goes through the participant-owned
 * {@code PATCH /api/channels/{id}} route).
 */
public record RenameChannelRequest(@NotBlank @Size(max = 100) String name) {
}
