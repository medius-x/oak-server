package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.config.TurnProperties;
import oak.server.dto.CallConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Hands clients the WebRTC ICE/TURN config (from {@code oak.turn.*}) for peer-connection setup.
 * JWT-protected like the rest of the API (only {@code /api/auth/**} is public).
 */
@RestController
@RequestMapping("/api/call")
@RequiredArgsConstructor
public class CallController {

    private final TurnProperties turnProperties;

    @GetMapping("/config")
    public CallConfig config() {
        String base = turnProperties.host() + ":" + turnProperties.port();
        List<String> urls = List.of(
                "stun:" + base,
                "turn:" + base + "?transport=udp",
                "turn:" + base + "?transport=tcp");
        return new CallConfig(List.of(
                new CallConfig.IceServer(urls, turnProperties.user(), turnProperties.password())));
    }
}
