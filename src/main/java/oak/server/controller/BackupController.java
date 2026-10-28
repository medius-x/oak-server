package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.BackupEstimateResponse;
import oak.server.dto.BackupStatusResponse;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import oak.server.service.BackupService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
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
        return BackupStatusResponse.of(backupService.isRunning(), backupService.lastReport(),
                backupService.fileAvailable());
    }

    /** Whether a pass is running + the latched report of the last one + whether the
     *  transfer buffer still exists (it does not after a full delivery). */
    @GetMapping("/status")
    public BackupStatusResponse status(@AuthenticationPrincipal UserDetails principal) {
        requireAdministrator(principal);
        return BackupStatusResponse.of(backupService.isRunning(), backupService.lastReport(),
                backupService.fileAvailable());
    }

    /** Byte-size preview for the confirmation dialog (exact for objects, an upper bound
     *  for the SQL) plus the free space of the archive store — the client refuses to start
     *  a pass the disk cannot hold. No archive is built or read. */
    @GetMapping("/estimate")
    public BackupEstimateResponse estimate(@AuthenticationPrincipal UserDetails principal) {
        requireAdministrator(principal);
        BackupService.BackupEstimate estimate = backupService.estimate();
        return new BackupEstimateResponse(
                estimate.objectBytes(), estimate.objectCount(), estimate.approxDatabaseBytes(),
                estimate.availableBytes());
    }

    /** Streams the finished archive (NOT a byte[] — the zip may be gigabytes). The name
     *  carries a timestamp; the client re-names to {@code server-backup-<host>-<stamp>.zip}
     *  for its retention sweep. The stream DELETES the server copy once it has been read to
     *  the end — the server keeps no built archive (user decision 2026-10-28); a truncated
     *  download leaves the file for a retry. */
    @GetMapping("/file")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal UserDetails principal) {
        requireAdministrator(principal);
        if (!backupService.fileAvailable()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No backup archive is waiting for download — take a backup first");
        }
        long size = backupService.backupFile().length();
        String fileName = "oak-backup-" + STAMP.format(LocalDateTime.now()) + ".zip";
        InputStreamResource body;
        try {
            body = new InputStreamResource(backupService.openArchiveForDelivery());
        } catch (IOException openError) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The backup archive could not be opened: " + openError.getMessage());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }

    /** Deletes the undelivered archive without downloading it (an interrupted download
     *  leaves the buffer on the server; the owner may just want it gone). Idempotent 204 —
     *  the client only shows the button while the file exists anyway. */
    @DeleteMapping("/file")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@AuthenticationPrincipal UserDetails principal) {
        requireAdministrator(principal);
        backupService.discardArchive();
    }

    private void requireAdministrator(UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireAdministrator(actor);
    }
}
