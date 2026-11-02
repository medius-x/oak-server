package oak.server.dto;

import java.time.Instant;

/**
 * The one-shot transfer package for the server-to-server migration leg (2026-11-02). The
 * RAW token appears here exactly once — the client hands it to the NEW server, which
 * presents it as the {@code X-Space-Migration-Token} header on the transfer endpoints.
 * {@code archiveSha256}/{@code archiveBytes} let the receiving side verify the transfer
 * after it lands (and drive an honest byte- progress while it runs).
 */
public record MigrationTransferResponse(String token, Instant expiresAt,
                                        String archiveSha256, long archiveBytes) {
}
