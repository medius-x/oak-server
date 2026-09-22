package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.CreateDiscussionRequest;
import oak.server.dto.DiscussionResponse;
import oak.server.service.DiscussionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Discussion (thread) endpoints. Two route roots deliberately live on ONE controller with
 * explicit full paths: the channel-scoped {@code POST/GET /api/channels/{channelId}/discussions}
 * and the discussion-scoped {@code /api/discussions/...}. No class-level {@code @RequestMapping}
 * prefix — the DmChannelController mapping-collision class (a bare {@code /{id}} segment on a
 * shared root) is avoided by keeping every path literal.
 */
@RestController
@RequiredArgsConstructor
public class DiscussionController {

    private final DiscussionService discussionService;

    /** Start a discussion on one message of the channel. 201 with the discussion (snapshot
     *  fields + tracked=true — the creator follows it). Bare {@code messageId} creates the
     *  empty discussion (409 when one exists); with {@code content} (the draft's first
     *  reply) the discussion is created WITH that message and a conflict reuses the
     *  existing thread — the client only ever uses the content path. */
    @PostMapping("/api/channels/{channelId}/discussions")
    @ResponseStatus(HttpStatus.CREATED)
    public DiscussionResponse create(@PathVariable Long channelId,
                                     @RequestBody @Valid CreateDiscussionRequest request,
                                     @AuthenticationPrincipal UserDetails principal) {
        return discussionService.create(channelId, request, principal.getUsername());
    }

    /** Every discussion under the channel (the tiles under origin messages), tracked-flagged
     *  for THIS viewer. Gate: READ_MESSAGE_HISTORY on the channel. */
    @GetMapping("/api/channels/{channelId}/discussions")
    public List<DiscussionResponse> listForChannel(@PathVariable Long channelId,
                                                   @AuthenticationPrincipal UserDetails principal) {
        return discussionService.listForParent(channelId, principal.getUsername());
    }

    /** The discussions THIS viewer follows (across every readable channel), most recently
     *  active first — the sidebar's discussions list. */
    @GetMapping("/api/discussions/mine")
    public List<DiscussionResponse> listMine(@AuthenticationPrincipal UserDetails principal) {
        return discussionService.listMine(principal.getUsername());
    }

    @GetMapping("/api/discussions/{discussionId}")
    public DiscussionResponse get(@PathVariable Long discussionId,
                                  @AuthenticationPrincipal UserDetails principal) {
        return discussionService.get(discussionId, principal.getUsername());
    }

    /** Follow the discussion (idempotent). */
    @PutMapping("/api/discussions/{discussionId}/track")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void track(@PathVariable Long discussionId,
                      @AuthenticationPrincipal UserDetails principal) {
        discussionService.track(discussionId, principal.getUsername());
    }

    /** Unfollow (idempotent, personal only). */
    @DeleteMapping("/api/discussions/{discussionId}/track")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void untrack(@PathVariable Long discussionId,
                        @AuthenticationPrincipal UserDetails principal) {
        discussionService.untrack(discussionId, principal.getUsername());
    }

    /** Close the discussion for everyone (MANAGE_MESSAGES on the parent channel). 204; the
     *  REMOVED push on the parent topic is the signal clients act on. */
    @DeleteMapping("/api/discussions/{discussionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable Long discussionId,
                      @AuthenticationPrincipal UserDetails principal) {
        discussionService.close(discussionId, principal.getUsername());
    }
}
