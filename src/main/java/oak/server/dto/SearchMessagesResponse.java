package oak.server.dto;

import java.util.List;

/**
 * One search page: the cursor-independent total hit count (constant across the client's
 * pages) plus the newest-first page of results (page with {@code beforeId = last id}).
 * A brand-new endpoint's response — no wire-compatibility constraints.
 *
 * <p>{@code discussions} (appended 2026-11-18): the thread context for THIS page's hits
 * that live in discussion threads — resolved by {@code MessageResponse.channelId} ↔
 * {@code DiscussionHit.id}. Empty when the page has no thread hits or the caller did not
 * opt in ({@code includeDiscussions}); absent entirely on pre-thread-search servers (the
 * client treats absence as empty).
 */
public record SearchMessagesResponse(long total, List<MessageResponse> results,
                                     List<DiscussionHit> discussions) {

    /** The pre-thread-search shape — kept for the plain call sites (tests). */
    public SearchMessagesResponse(long total, List<MessageResponse> results) {
        this(total, results, List.of());
    }
}
