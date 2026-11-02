package oak.server.controller;

import lombok.RequiredArgsConstructor;
import oak.server.dto.BackupEstimateResponse;
import oak.server.dto.BackupStatusResponse;
import oak.server.dto.MigrationTransferResponse;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import oak.server.service.BackupService;
import oak.server.service.MigrationTransferService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private final MigrationTransferService migrationTransfers;

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
     *  download leaves the file for a retry.
     *
     *  <p><b>Range support (2026-11-02):</b> a single {@code Range: bytes=…} header is
     *  answered with 206 — the server-to-server migration leg transfers the archive with
     *  {@code curl -C -}, which can only RESUME on 206 (without ranges an interrupted
     *  multi-gigabyte transfer restarts from zero). Unparseable, multi-range or unknown-unit
     *  headers are IGNORED per RFC 9110 (a full 200 answers); an unsatisfiable range gets
     *  the canonical 416 whose unsatisfied-range Content-Range carries the total size —
     *  the combination {@code curl -C -} relies on to recognize "already complete". */
    @GetMapping("/file")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal UserDetails principal,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        requireAdministrator(principal);
        if (!backupService.fileAvailable()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No backup archive is waiting for download — take a backup first");
        }
        long size = backupService.backupFile().length();
        String fileName = "oak-backup-" + STAMP.format(LocalDateTime.now()) + ".zip";
        long[] range = parseRange(rangeHeader, size);
        if (range == UNSATISFIABLE) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                    .build();
        }
        long start = range == null ? 0 : range[0];
        long length = range == null ? size : range[1] - range[0] + 1;
        InputStreamResource body;
        try {
            body = new InputStreamResource(backupService.openArchiveForDelivery(start, length));
        } catch (IOException openError) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The backup archive could not be opened: " + openError.getMessage());
        }
        ResponseEntity.BodyBuilder answer = range == null
                ? ResponseEntity.ok()
                : ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes " + range[0] + "-" + range[1] + "/" + size);
        return answer
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(length)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }

    /** Marker for "the header is a syntactically valid range that the archive cannot
     *  satisfy" (every byte position beyond the end) — the caller answers 416. */
    private static final long[] UNSATISFIABLE = new long[0];

    /** Single-range {@code bytes=…} parser. Null → serve the FULL body (no header, or one
     *  this server legitimately ignores: multi-range, non-bytes units, garbage — RFC 9110
     *  says an origin server MUST ignore such headers, not fail on them). Returns
     *  {@code [start, end]} inclusive on success. */
    private static long[] parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startText = spec.substring(0, dash).trim();
        String endText = spec.substring(dash + 1).trim();
        try {
            if (startText.isEmpty()) {
                // Suffix form "bytes=-N": the LAST N bytes of the archive.
                if (endText.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return UNSATISFIABLE;
                }
                return size == 0 ? UNSATISFIABLE : new long[]{Math.max(0, size - suffix), size - 1};
            }
            long start = Long.parseLong(startText);
            if (start >= size) {
                return UNSATISFIABLE;
            }
            long end = endText.isEmpty() ? size - 1 : Math.min(Long.parseLong(endText), size - 1);
            if (end < start) {
                return null;
            }
            return new long[]{start, end};
        } catch (NumberFormatException garbage) {
            return null;
        }
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

    // ── The server-to-server transfer leg (2026-11-02) ─────────────────────────
    // The NEW server pulls the archive straight from this one (curl + the one-shot
    // token below); the user's home link stays out of the byte path. Bearer-gated
    // like the rest of the block — only the CONSUMING endpoints (transfer/*) walk
    // on the token alone.

    /** Issues the one-shot migration token bound to the CURRENTLY built archive
     *  (its digest and size ride along — the receiving server verifies the transfer
     *  after it lands). Re-issuing displaces any previous token; 409 when no archive
     *  is waiting. */
    @PostMapping("/migration-transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public MigrationTransferResponse issueMigrationTransfer(@AuthenticationPrincipal UserDetails principal) {
        requireAdministrator(principal);
        BackupService.BackupReport report = backupService.lastReport();
        if (report == null || report.finishedAt == null || report.archiveSha256 == null
                || !backupService.fileAvailable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No built archive is waiting for a transfer — take a backup first");
        }
        MigrationTransferService.Issued issued = migrationTransfers.issue();
        return new MigrationTransferResponse(issued.token(), issued.expiresAt(),
                report.archiveSha256, report.sizeBytes);
    }

    /** Withdraws the token (the migration ended, was cancelled, or fell back to the
     *  through-this-computer route). Idempotent 204. */
    @DeleteMapping("/migration-transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeMigrationTransfer(@AuthenticationPrincipal UserDetails principal) {
        requireAdministrator(principal);
        migrationTransfers.revoke();
    }

    /** The receiving server's reachability probe: token valid + archive waiting → 204.
     *  Bodyless on purpose — reachability and validity are ALL the probe learns (401 vs
     *  404 keeps the two failure classes distinguishable for the client's fallback
     *  decision). PermitAll-path: the entire gate is the token itself. */
    @GetMapping("/transfer/status")
    public ResponseEntity<Void> transferStatus(
            @RequestHeader(value = MIGRATION_TOKEN_HEADER, required = false) String token) {
        if (!migrationTransfers.isValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!backupService.fileAvailable()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.noContent().build();
    }

    /** The archive for the RECEIVING server: same streaming (and the same Range/206
     *  resume) as the Bearer download, but gated by the migration token alone — the new
     *  server has no login on this machine. One transfer at a time (429 while another
     *  delivery stream is alive); the slot is released when the stream closes, drained
     *  or aborted. */
    @GetMapping("/transfer/file")
    public ResponseEntity<Resource> transferFile(
            @RequestHeader(value = MIGRATION_TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        if (!migrationTransfers.beginTransfer(token)) {
            return ResponseEntity.status(migrationTransfers.isValid(token)
                    ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.UNAUTHORIZED).build();
        }
        if (!backupService.fileAvailable()) {
            migrationTransfers.endTransfer();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        long size = backupService.backupFile().length();
        long[] range = parseRange(rangeHeader, size);
        if (range == UNSATISFIABLE) {
            migrationTransfers.endTransfer();
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                    .build();
        }
        long start = range == null ? 0 : range[0];
        long length = range == null ? size : range[1] - range[0] + 1;
        Resource body;
        try {
            body = new InputStreamResource(new TransferStream(backupService.openArchiveForDelivery(start, length)));
        } catch (IOException openError) {
            migrationTransfers.endTransfer();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The backup archive could not be opened: " + openError.getMessage());
        }
        ResponseEntity.BodyBuilder answer = range == null
                ? ResponseEntity.ok()
                : ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes " + range[0] + "-" + range[1] + "/" + size);
        return answer
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(length)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(body);
    }

    /** The token the receiving server presents on the transfer endpoints. */
    static final String MIGRATION_TOKEN_HEADER = "X-Space-Migration-Token";

    /** Releases the single transfer slot exactly when the delivery stream closes —
     *  drained to the end OR aborted by the network, both go through close(). */
    private final class TransferStream extends FilterInputStream {
        TransferStream(InputStream raw) {
            super(raw);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                migrationTransfers.endTransfer();
            }
        }
    }

    private void requireAdministrator(UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireAdministrator(actor);
    }
}
