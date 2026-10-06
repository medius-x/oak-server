package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.ServerHealthResponse;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import oak.server.service.ServerHealthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/server/health} — the live backing-services status shown in Server Settings
 * → Server. Gated by {@link Permission#MANAGE_STORAGE} like the rest of the Server tab: the
 * details name local endpoints and container states, which is operator information.
 */
@RestController
@RequestMapping("/api/server/health")
@RequiredArgsConstructor
public class ServerHealthController {

    private final ServerHealthService serverHealthService;
    private final AuthorizationService auth;

    @GetMapping
    public ServerHealthResponse health(@AuthenticationPrincipal UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireServer(actor, Permission.MANAGE_STORAGE);
        return serverHealthService.collect();
    }
}
