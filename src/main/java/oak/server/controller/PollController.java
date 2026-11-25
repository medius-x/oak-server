package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.CreatePollRequest;
import oak.server.dto.MessageResponse;
import oak.server.dto.PollResults;
import oak.server.dto.VotePollRequest;
import oak.server.service.MessageService;
import oak.server.service.PollService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Poll endpoints (2026-11-25). Creation posts into a channel (SEND_MESSAGES + CREATE_POLLS);
 * voting, results and the early close address the poll by id — the channel and its permission
 * context resolve through the poll's own message (a poll id from a hidden channel reads as a
 * plain 404-level denial, the findByIdAndChannelId scoping idiom). Auto-secured by Spring
 * Security (JWT) like the rest of {@code /api/**}.
 */
@RestController
@RequiredArgsConstructor
public class PollController {

    private final MessageService messageService;
    private final PollService pollService;

    /** Creates the poll message and broadcasts it on the channel's base topic. */
    @PostMapping("/api/channels/{channelId}/polls")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse create(@PathVariable Long channelId,
                                  @Valid @RequestBody CreatePollRequest request,
                                  @AuthenticationPrincipal UserDetails principal) {
        return messageService.createPoll(channelId, request, principal.getUsername());
    }

    /** Casts (or replaces) the caller's vote; the answer carries the tallied results. */
    @PostMapping("/api/polls/{pollId}/vote")
    public PollResults vote(@PathVariable Long pollId,
                            @Valid @RequestBody VotePollRequest request,
                            @AuthenticationPrincipal UserDetails principal) {
        return pollService.vote(pollId, request, principal.getUsername());
    }

    /** The tallied results — available to everyone who can read the channel. */
    @GetMapping("/api/polls/{pollId}/results")
    public PollResults results(@PathVariable Long pollId,
                               @AuthenticationPrincipal UserDetails principal) {
        return pollService.results(pollId, principal.getUsername());
    }

    /** Early finish — the author, OWNER/ADMINISTRATOR, or MANAGE_MESSAGES (idempotent). */
    @PostMapping("/api/polls/{pollId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable Long pollId,
                      @AuthenticationPrincipal UserDetails principal) {
        pollService.close(pollId, principal.getUsername());
    }
}
