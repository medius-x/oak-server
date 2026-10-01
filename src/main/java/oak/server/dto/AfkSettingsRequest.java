package oak.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/server/afk body: {@code afkChannelId} null DISABLES the sweep (the picker's
 * "Disabled" option); the timeout stays within 5 minutes..7 days.
 */
public record AfkSettingsRequest(Long afkChannelId,
                                 @NotNull @Min(5) @Max(10_080) Integer afkTimeoutMinutes) {
}
