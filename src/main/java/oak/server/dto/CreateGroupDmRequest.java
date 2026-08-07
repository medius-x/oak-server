package oak.server.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code POST /api/channels/group-dm}. {@code participantIds} are the <em>other</em> members
 * (the caller is added as owner on top), so 2–9 ids yield a group of 3–10 (the Discord group-DM range).
 * The service re-validates the effective size after de-duplicating and dropping the caller.
 */
public record CreateGroupDmRequest(
        @Size(min = 2, max = 9) List<Long> participantIds,
        @Size(max = 100) String name) {
}
