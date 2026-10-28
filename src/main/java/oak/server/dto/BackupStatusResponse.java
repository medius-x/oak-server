package oak.server.dto;

import oak.server.service.BackupService;

import java.time.Instant;

/**
 * Poll payload of the backup block (Server settings → Server): whether a pass is running,
 * the latched report of the last one, and whether the transfer buffer still exists —
 * "Download latest" only works while it does (the archive is deleted after a full delivery).
 * Wire-shape mirror of {@code StorageStatusResponse}.
 */
public record BackupStatusResponse(boolean running, BackupRunResponse lastRun, boolean fileAvailable) {

    public static BackupStatusResponse of(boolean running, BackupService.BackupReport report,
                                          boolean fileAvailable) {
        return new BackupStatusResponse(running, report == null ? null : BackupRunResponse.from(report),
                fileAvailable);
    }

    /** The finished (or in-progress — fields fill in as it goes) pass. */
    public record BackupRunResponse(Instant startedAt, Instant finishedAt, long sizeBytes,
                                    String flywayVersion, String appVersion, long totalRows,
                                    long objectCount, long objectBytes, long failedObjects,
                                    String error) {
        static BackupRunResponse from(BackupService.BackupReport report) {
            return new BackupRunResponse(report.startedAt, report.finishedAt, report.sizeBytes,
                    report.flywayVersion, report.appVersion, report.totalRows(),
                    report.objectCount, report.objectBytes, report.failedObjects, report.error);
        }
    }
}
