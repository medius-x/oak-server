package oak.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * TTL change body ({@code PUT /api/server/invite-settings}). One minute to one year —
 * retroactively applied to every active invite ({@code createdAt + ttl}, user decision).
 */
public record InviteSettingsRequest(
        @NotNull @Min(1) @Max(525_600) Integer ttlMinutes) {
}
