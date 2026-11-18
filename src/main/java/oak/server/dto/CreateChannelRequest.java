package oak.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import oak.server.entity.ChannelType;

/** {@code type} may be {@code CATEGORY} (the collapsible sidebar section); then
 *  {@code parentId} must be null — categories are always top level. For a
 *  {@code TEXT}/{@code VOICE} channel {@code parentId} optionally names the
 *  category to group under (ChannelService validates it exists and is a
 *  CATEGORY). {@code userLimit} (V18) is the VOICE room's participant limit —
 *  optional (null = no limit), rejected on non-VOICE types. */
public record CreateChannelRequest(
        @NotBlank @Size(max = 100) String name,
        ChannelType type, // optional; defaults to TEXT
        Long parentId, // optional; the grouping CATEGORY
        Integer userLimit // optional; VOICE only, 1..99, null = unlimited
) {

    /** The pre-category shape (kept for tests and no-parent call sites). */
    public CreateChannelRequest(String name, ChannelType type) {
        this(name, type, null, null);
    }

    /** The pre-userLimit shape. */
    public CreateChannelRequest(String name, ChannelType type, Long parentId) {
        this(name, type, parentId, null);
    }
}
