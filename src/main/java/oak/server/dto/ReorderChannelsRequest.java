package oak.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** PUT /api/channels/order — the full-sidebar reorder (Discord's drag-drop persist).
 *  The entries list EVERY non-DM channel and category exactly once, in the intended
 *  sidebar order; the server renumbers {@code position} densely by list index and
 *  applies each entry's {@code parentId} in the same transaction. A partial list,
 *  a duplicated id or a foreign id is a 400. */
public record ReorderChannelsRequest(
        @NotNull @Valid List<ChannelOrderEntry> entries
) {
}
