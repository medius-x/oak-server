package oak.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Storage-policy edit ({@code PUT /api/server/storage}): the disk-full percent that triggers a
 * purge, the percent to purge down to, the local minute-of-day the nightly GC fires at, and
 * whether automatic cleanup runs at all (off = manual runs only; the event tier is unaffected).
 */
public record StorageSettingsRequest(
        @Min(1) @Max(99) Integer highWatermarkPercent,
        @Min(0) @Max(98) Integer lowWatermarkPercent,
        @Min(0) @Max(1439) Integer gcMinuteOfDay,
        Boolean gcEnabled
) {
}
