package oak.server.dto;

import jakarta.validation.constraints.NotNull;

/** One row of {@link ReorderChannelsRequest}: the channel (or category) id in its
 *  list position, and the category it groups under — {@code null} = top level. */
public record ChannelOrderEntry(
        @NotNull Long id,
        Long parentId
) {
}
