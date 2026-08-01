package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/server} — renames the instance. {@code MANAGE_SERVER}-gated server-side.
 */
public record UpdateServerNameRequest(@NotBlank @Size(max = 100) String name) {
}
