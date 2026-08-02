package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.ChannelReadStateResponse;
import oak.server.service.ReadStateService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Per-(user, channel) read state. {@code GET /api/read-states} returns the unread/mention snapshot
 * the client seeds its sidebar badges from; {@code POST /api/channels/{channelId}/read} advances the
 * read pointer (the client acks when it opens or leaves a channel). Both are open to any member with
 * {@code READ_MESSAGE_HISTORY} in the channel (enforced in the service).
 */
@RestController
@RequiredArgsConstructor
public class ReadStateController {

    private final ReadStateService readStateService;

    @GetMapping("/api/read-states")
    public List<ChannelReadStateResponse> snapshot(@AuthenticationPrincipal UserDetails principal) {
        return readStateService.snapshot(principal.getUsername());
    }

    /** Marks the channel read up to {@code lastReadMessageId} (or up to the newest message when the
     *  body is omitted/empty). Always 204 — fire-and-forget from the client. */
    @PostMapping("/api/channels/{channelId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ack(@AuthenticationPrincipal UserDetails principal,
                    @PathVariable long channelId,
                    @RequestBody(required = false) AckRequest body) {
        Long lastRead = body == null ? null : body.lastReadMessageId();
        readStateService.ack(principal.getUsername(), channelId, lastRead);
    }

    /** Optional ack body: the highest message id the client has rendered. */
    public record AckRequest(Long lastReadMessageId) {
    }
}
