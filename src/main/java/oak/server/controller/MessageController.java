package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.CreateMessageRequest;
import oak.server.dto.MessageResponse;
import oak.server.service.MessageService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Messages within a channel. Creating a message is the REST write path (variant A);
 * real-time delivery to other clients arrives with the WebSocket roadmap item.
 * All endpoints require a valid JWT.
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
}
