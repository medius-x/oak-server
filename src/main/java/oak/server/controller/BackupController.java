package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.BackupEstimateResponse;
import oak.server.dto.BackupStatusResponse;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import oak.server.service.BackupService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The backup block of the "Server" settings tab: build the full-instance archive (messages +
 * every stored object), poll its progress, preview its size, and download the finished zip.
 *
 * <p>Gated by OWNER-or-ADMINISTRATOR (user decision 2026-10-28) — the archive carries EVERY
 * conversation, so a narrow {@code MANAGE_SERVER} holder is deliberately NOT enough; see
 * {@link AuthorizationService#requireAdministrator(User)}. The owner passes through the
 * OWNER→ALL short-circuit like everywhere else.
 */
@RestController
@RequestMapping("/api/server/backup")
@RequiredArgsConstructor
public class BackupController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AuthorizationService auth;
    private final BackupService backupService;

    /** Fires one backup pass NOW (202; the run itself is background — poll GET /status). */
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BackupStatusResponse runNow(@AuthenticationPrincipal UserDetails principal) {
        requireAdministrator(principal);
        try {
            backupService.startRun();
        } catch (IllegalStateException busy) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, busy.getMessage());
        }
        return BackupStatusResponse.of(backupService.isRunning(), backupService.lastReport());
    }

    /** Whether a pass is running + the latched report of the last one. */
    @GetMapping("/status")
    public BackupStatusResponse status(@AuthenticationPrincipal UserDetails principal) {
        requireAdministrator(principal);
        return BackupStatusResponse.of(backupService.isRunning(), backupService.lastReport());
    }

    /** Byte-size preview for the confirmation dialog (exact for objects, an upper bound
     *  for the SQL). No archive is built or read. */
    @GetMapping("/estimate")
    public BackupEstimateResponse estimate(@AuthenticationPrincipal UserDetails principal) {
        requireAdministrator(principal);
        BackupService.BackupEstimate estimate = backupService.estimate();
        return new BackupEstimateResponse(
                estimate.objectBytes(), estimate.objectCount(), estimate.approxDatabaseBytes());
    }

    /** Streams the finished archive (NOT a byte[] — the zip may be gigabytes). The name
     *  carries a timestamp; the client re-names to {@code server-backup-<host>-<stamp>.zip}
     *  for its retention sweep. */
    @GetMapping("/file")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal UserDetails principal) {
        requireAdministrator(principal);
        java.io.File file = backupService.backupFile();
        if (!file.isFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No backup has been built yet");
        }
        String fileName = "oak-backup-" + STAMP.format(LocalDateTime.now()) + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(new FileSystemResource(file));
    }

    private void requireAdministrator(UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireAdministrator(actor);
    }
}
