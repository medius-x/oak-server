package oak.server.dto;

import oak.server.service.StorageGcService;

import java.time.Instant;

/**
 * One GC pass — the immediate answer to {@code POST /run} (started, nothing measured yet) and
 * the latched {@code lastRun} in the status (filled in as the run finished).
 */
public record StorageGcRunResponse(
        String trigger,
        Instant startedAt,
        Instant finishedAt,
        String outcome,
        long sweptOrphanObjects,
        long sweptBytes,
        long purgedMessages,
        long purgedAttachmentObjects,
        double finalUsedPercent
) {

    public static StorageGcRunResponse started() {
        return new StorageGcRunResponse("manual", Instant.now(), null, "started", 0, 0, 0, 0, -1);
    }

    public static StorageGcRunResponse from(StorageGcService.StorageGcReport report) {
        return new StorageGcRunResponse(report.trigger, report.startedAt, report.finishedAt,
                report.outcome, report.sweptOrphanObjects, report.sweptBytes, report.purgedMessages,
                report.purgedAttachmentObjects, report.finalUsedPercent);
    }
}
