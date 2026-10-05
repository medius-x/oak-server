package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.config.TurnProperties;
import oak.server.dto.CallConfig;
import oak.server.service.TurnCredentialIssuer;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Hands clients the WebRTC ICE/TURN config (from {@code oak.turn.*}) for peer-connection setup.
 * JWT-protected like the rest of the API (only {@code /api/auth/**} is public).
 *
 * <p>Each call mints a FRESH short-lived TURN credential for the requesting member (coturn
 * REST-API ticket over {@code --use-auth-secret}); the wire shape is unchanged — the client
 * still drops {@code username}/{@code credential} into the RTCIceServer as-is.
 */
@RestController
@RequestMapping("/api/call")
@RequiredArgsConstructor
public class CallController {

    private final TurnProperties turnProperties;
    private final TurnCredentialIssuer turnCredentialIssuer;

    @GetMapping("/config")
    public CallConfig config(@AuthenticationPrincipal UserDetails principal) {
        String base = turnProperties.host() + ":" + turnProperties.port();
        List<String> urls = List.of(
                "stun:" + base,
                "turn:" + base + "?transport=udp",
                "turn:" + base + "?transport=tcp");
        TurnCredentialIssuer.TurnCredential credential =
                turnCredentialIssuer.issue(principal.getUsername(), Instant.now());
        return new CallConfig(List.of(
                new CallConfig.IceServer(urls, credential.username(), credential.credential())));
    }
}
