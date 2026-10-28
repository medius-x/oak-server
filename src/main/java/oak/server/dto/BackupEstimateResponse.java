package oak.server.dto;

/**
 * Size preview of a future backup, for the "Back up now?" confirmation dialog: the exact
 * byte sum of the bucket listing plus an upper bound of the SQL dump (table heaps, indexes
 * excluded), plus the free space of the archive directory's file system. The client
 * refuses to start a pass when {@code availableBytes} (≥0) is below the projected archive
 * size — a nearly-full disk below the cleanup watermark would otherwise die mid-build.
 * Answers in seconds — no bytes are read.
 */
public record BackupEstimateResponse(long objectBytes, long objectCount, long approxDatabaseBytes,
                                     long availableBytes) {}
