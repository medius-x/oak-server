package oak.server.dto;

/**
 * The "Server" settings screen payload: the policy, the live disk reading, whether a pass is
 * running right now, and the last finished (or failed) pass.
 */
public record StorageStatusResponse(
        Policy policy,
        Usage usage,
        boolean running,
        StorageGcRunResponse lastRun
) {

    public record Policy(int highWatermarkPercent, int lowWatermarkPercent, int gcMinuteOfDay,
                         boolean gcEnabled) {}

    /** The measured filesystem: sizes in bytes, usage 0–100. */
    public record Usage(long totalBytes, long usedBytes, double usedPercent) {}
}
