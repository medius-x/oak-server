package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.CreateMessageRequest;
import oak.server.dto.EditMessageRequest;
import oak.server.dto.MessageResponse;
import oak.server.dto.ReactionSummary;
import oak.server.service.MessageService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Messages within a channel. Creating a message is the REST write path (variant A);
 * real-time delivery to other clients arrives with the WebSocket roadmap item.
 * Edit/delete are the same variant-A pattern: REST write, then a WS push on the
 * channel's {@code .edited}/{@code .deleted} topics. All endpoints require a valid JWT.
 */
@RestController
@RequestMapping("/api/channels/{channelId}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping
    public List<MessageResponse> list(@PathVariable Long channelId,
                                      @RequestParam(required = false) Integer limit,
                                      @AuthenticationPrincipal UserDetails principal) {
        return messageService.listByChannel(channelId, limit, principal.getUsername());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse create(@PathVariable Long channelId,
                                  @RequestBody @Valid CreateMessageRequest request,
                                  @AuthenticationPrincipal UserDetails principal) {
        return messageService.create(channelId, request, principal.getUsername());
    }

    @PatchMapping("/{messageId}")
    public MessageResponse edit(@PathVariable Long channelId,
                                @PathVariable Long messageId,
                                @RequestBody @Valid EditMessageRequest request,
                                @AuthenticationPrincipal UserDetails principal) {
        return messageService.edit(channelId, messageId, request, principal.getUsername());
    }

    @DeleteMapping("/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long channelId,
                       @PathVariable Long messageId,
                       @AuthenticationPrincipal UserDetails principal) {
        messageService.delete(channelId, messageId, principal.getUsername());
    }

    /** Adds the caller's reaction (idempotent). 200 with the message's updated reaction
     *  snapshot — the client applies this answer directly and the WS echo lands idempotently
     *  on top. */
    @PutMapping("/{messageId}/reactions/{slug}")
    public List<ReactionSummary> addReaction(@PathVariable Long channelId,
                                             @PathVariable Long messageId,
                                             @PathVariable String slug,
                                             @AuthenticationPrincipal UserDetails principal) {
        return messageService.addReaction(channelId, messageId, slug, principal.getUsername());
    }

    /** Removes the caller's reaction (idempotent). Same 200-with-snapshot contract as
     *  {@link #addReaction}. */
    @DeleteMapping("/{messageId}/reactions/{slug}")
    public List<ReactionSummary> removeReaction(@PathVariable Long channelId,
                                                @PathVariable Long messageId,
                                                @PathVariable String slug,
                                                @AuthenticationPrincipal UserDetails principal) {
        return messageService.removeReaction(channelId, messageId, slug, principal.getUsername());
    }
}
