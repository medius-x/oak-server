package oak.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /api/channels/{channelId}/polls}. The duration arrives in MINUTES (the client's
 * presets and the free-form days/hours/minutes input both resolve to minutes before sending);
 * {@code null} = unlimited. Server-side bounds (user decision 2026-11-25): question ≤ 300,
 * 1..10 options of ≤ 100 chars each — the API floor is one option; the client dialog asks for
 * at least two, the wire contract does not force its UX floor onto hand-rolled callers.
 */
public record CreatePollRequest(
        @NotBlank @Size(max = 300) String question,
        @NotNull @Size(min = 1, max = 10) List<@NotBlank @Size(max = 100) String> options,
        Boolean allowMultiple,
        Boolean anonymous,
        Boolean allowVoteChange,
        @Min(1) @Max(525600) Integer durationMinutes
) {
}
