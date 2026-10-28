package oak.server.dto;

import oak.server.service.BackupService;

import java.time.Instant;

/**
 * Poll payload of the backup block (Server settings → Server): whether a pass is running and
 * the latched report of the last one. Wire-shape mirror of {@code StorageStatusResponse}.
 */
public record BackupStatusResponse(boolean running, BackupRunResponse lastRun) {

    public static BackupStatusResponse of(boolean running, BackupService.BackupReport report) {
        return new BackupStatusResponse(running, report == null ? null : BackupRunResponse.from(report));
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
