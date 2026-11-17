package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.SearchMessagesResponse;
import oak.server.service.MessageService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Message search (Discord-style substring matching). One endpoint covers both scopes:
 * {@code channelId} given — that channel (a DM/group-DM works, the access gate degenerates
 * to the participant check); omitted — the whole server (every readable TEXT channel; DMs
 * are never searched server-wide). {@code q} and {@code author} are both optional filters,
 * at least one required. Results are newest-first, paged with {@code beforeId}.
 *
 * <p>{@code includeDiscussions} (2026-11-18): threads join the search — a channel scope
 * covers the channel PLUS its discussions, the server-wide scope covers every readable
 * discussion too, and the response carries {@code discussions} context for thread hits.
 * The parameter is the COMPATIBILITY gate itself: pre-thread-search clients never send
 * it and get the old, thread-less behaviour (they cannot jump into a thread).
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final MessageService messageService;

    @GetMapping("/messages")
    public SearchMessagesResponse search(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) Long channelId,
                                         @RequestParam(required = false) String author,
                                         @RequestParam(required = false) Long beforeId,
                                         @RequestParam(required = false) Integer limit,
                                         @RequestParam(required = false) Boolean includeDiscussions,
                                         @AuthenticationPrincipal UserDetails principal) {
        return messageService.searchMessages(q, channelId, author, beforeId, limit,
                principal.getUsername(), Boolean.TRUE.equals(includeDiscussions));
    }
}
