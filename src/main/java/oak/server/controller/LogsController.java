package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import oak.server.service.ServerLogsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The logs download of the "Server" settings tab (logging round, 2026-10-06): one zip with
 * the server log of the last 24 hours, the peripheral container tails and a manifest. Gated
 * by {@link Permission#MANAGE_STORAGE} exactly like the rest of the tab — whoever can open
 * the tab can download its logs.
 */
@RestController
@RequestMapping("/api/server/logs")
@RequiredArgsConstructor
public class LogsController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AuthorizationService auth;
    private final ServerLogsService serverLogsService;

    @GetMapping
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireServer(actor, Permission.MANAGE_STORAGE);
        String fileName = "server-logs-" + STAMP.format(LocalDateTime.now()) + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(serverLogsService.buildZip());
    }
}
