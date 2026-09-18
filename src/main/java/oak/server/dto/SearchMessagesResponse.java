package oak.server.dto;

import java.util.List;

/**
 * One search page: the cursor-independent total hit count (constant across the client's
 * pages) plus the newest-first page of results (page with {@code beforeId = last id}).
 * A brand-new endpoint's response — no wire-compatibility constraints.
 */
public record SearchMessagesResponse(long total, List<MessageResponse> results) {
}
