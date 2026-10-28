package oak.server.dto;

/**
 * Size preview of a future backup, for the "Back up now?" confirmation dialog: the exact
 * byte sum of the bucket listing plus an upper bound of the SQL dump (table heaps, indexes
 * excluded). Answers in seconds — no bytes are read.
 */
public record BackupEstimateResponse(long objectBytes, long objectCount, long approxDatabaseBytes) {}
