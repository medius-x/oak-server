package oak.server.service;

import lombok.extern.slf4j.Slf4j;
import oak.server.dto.AttachmentRef;
import oak.server.entity.Message;
import oak.server.entity.ServerProfile;
import oak.server.repository.EmojiRepository;
import oak.server.repository.MessageReactionRepository;
import oak.server.repository.StickerRepository;
import oak.server.repository.MessageRepository;
import oak.server.repository.ServerProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;

/**
 * Storage garbage collection for a self-hosted instance. Two tiers:
 *
 * <p><b>Event tier</b> (no scheduler — see {@link MessageService}): when a message is deleted or
 * edited to drop attachments, each affected object key is checked against the remaining messages
 * (a forward referencing the key keeps it alive) and deleted the moment its last reference dies.
 *
 * <p><b>Watermark tier</b> (this service, nightly): when the DATA filesystem is fuller than the
 * high watermark, free space — cheapest first: sweep MinIO orphans (uploads never referenced by
 * any message), then batch-delete the OLDEST messages (reactions first — no ON DELETE CASCADE)
 * until the low watermark. Not age-based: history lives as long as the disk allows.
 *
 * <p><b>The Postgres trap:</b> DELETE does not return table space to the OS — only VACUUM FULL
 * rewrites the table (exclusive lock; seconds at our scale, minutes past ~5M messages). Two
 * guards: a no-progress detector stops the purge when deleting does not move the disk percent
 * (Postgres-dominated disk), and {@code oak.storage.vacuum-full} (default ON) rewrites
 * {@code messages} after a purge with {@code lock_timeout} so a busy table defers to the next
 * night instead of queueing behind writers.
 */
@Slf4j
@Service
public class StorageGcService {

    public static final int DEFAULT_HIGH_WATERMARK = 85;
    public static final int DEFAULT_LOW_WATERMARK = 50;
    public static final int DEFAULT_GC_MINUTE_OF_DAY = 3 * 60 + 30; // 03:30

    private static final int PURGE_BATCH_SIZE = 500;
    private static final int NO_PROGRESS_BATCHES = 3;
    /** How many ticks the scheduled run keeps retrying inside its window when the maintenance
     *  lock is held by a backup (backup round, 2026-10-28): the day is latched only AFTER a
     *  successful start, so a backup overlapping the configured minute defers the GC instead
     *  of silently skipping it for the whole day. */
    private static final int CATCHUP_TICKS = 6;

    private final ServerProfileRepository profileRepository;
    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final MessageService messageService;
    private final AttachmentObjectStore objectStore;
    private final EmojiRepository emojiRepository;
    private final StickerRepository stickerRepository;
    private final AttachmentListCodec attachmentListCodec;
    private final TransactionTemplate transactionTemplate;
    private final DataSource dataSource;
    private final StorageMaintenanceLock maintenanceLock;
    private final File measurePath;
    private final boolean vacuumFull;
    private final long minKeepMessages;
    private final Duration sweepGrace;
    private final Duration tickInterval;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile StorageGcReport lastReport = null;
    private volatile LocalDate lastScheduledRunDate = null;

    public StorageGcService(ServerProfileRepository profileRepository,
                            MessageRepository messageRepository,
                            MessageReactionRepository reactionRepository,
                            MessageService messageService,
                            AttachmentObjectStore objectStore,
                            EmojiRepository emojiRepository,
                            StickerRepository stickerRepository,
                            AttachmentListCodec attachmentListCodec,
                            TransactionTemplate transactionTemplate,
                            DataSource dataSource,
                            StorageMaintenanceLock maintenanceLock,
                            @Value("${oak.storage.path:.}") String measurePath,
                            @Value("${oak.storage.vacuum-full:true}") boolean vacuumFull,
                            @Value("${oak.storage.min-keep-messages:1000}") long minKeepMessages,
                            @Value("${oak.storage.sweep-grace-days:7}") long sweepGraceDays,
                            @Value("${oak.storage.gc-tick-interval:PT10M}") Duration tickInterval) {
        this.profileRepository = profileRepository;
        this.messageRepository = messageRepository;
        this.reactionRepository = reactionRepository;
        this.messageService = messageService;
        this.objectStore = objectStore;
        this.emojiRepository = emojiRepository;
        this.stickerRepository = stickerRepository;
        this.attachmentListCodec = attachmentListCodec;
        this.transactionTemplate = transactionTemplate;
        this.dataSource = dataSource;
        this.maintenanceLock = maintenanceLock;
        this.measurePath = new File(measurePath);
        this.vacuumFull = vacuumFull;
        this.minKeepMessages = minKeepMessages;
        this.sweepGrace = Duration.ofDays(sweepGraceDays);
        this.tickInterval = tickInterval;
    }

    /** The GC policy as the client sees it: stored values with code defaults for nulls
     *  (rows created before the columns existed). */
    public record StoragePolicy(int highWatermarkPercent, int lowWatermarkPercent, int gcMinuteOfDay,
                                boolean gcEnabled) {}

    public StoragePolicy policy() {
        ServerProfile profile = profileRepository.singleton();
        return new StoragePolicy(
                orDefault(profile.getStorageHighWatermarkPercent(), DEFAULT_HIGH_WATERMARK),
                orDefault(profile.getStorageLowWatermarkPercent(), DEFAULT_LOW_WATERMARK),
                orDefault(profile.getStorageGcMinuteOfDay(), DEFAULT_GC_MINUTE_OF_DAY),
                profile.getStorageGcEnabled() == null || profile.getStorageGcEnabled());
    }

    private static int orDefault(Integer stored, int fallback) {
        return stored == null ? fallback : stored;
    }

    /** Writes the policy; validation lives in the controller (400s), this just persists. */
    public void updatePolicy(int highWatermarkPercent, int lowWatermarkPercent, int gcMinuteOfDay,
                             boolean gcEnabled) {
        ServerProfile profile = profileRepository.singleton();
        profile.setStorageHighWatermarkPercent(highWatermarkPercent);
        profile.setStorageLowWatermarkPercent(lowWatermarkPercent);
        profile.setStorageGcMinuteOfDay(gcMinuteOfDay);
        profile.setStorageGcEnabled(gcEnabled);
        profileRepository.save(profile);
    }

    /** One disk measurement for the status screen. */
    public record StorageUsage(long totalBytes, long usedBytes, double usedPercent) {}

    public StorageUsage usage() {
        long total = measurePath.getTotalSpace();
        long usable = measurePath.getUsableSpace();
        long used = Math.max(0, total - usable);
        double percent = total == 0 ? 0 : used * 100.0 / total;
        return new StorageUsage(total, used, percent);
    }

    public boolean isRunning() {
        return running.get();
    }

    public StorageGcReport lastReport() {
        return lastReport;
    }

    /**
     * One full pass. Production measures the real filesystem; tests inject the percent through
     * the package-private seam. Returns the report (also latched as {@link #lastReport()}).
     * Throws {@link IllegalStateException} when the maintenance lock is held by a backup —
     * {@link StorageMaintenanceLock} keeps the two from racing over the same rows/objects.
     */
    public StorageGcReport runOnce(String trigger) {
        return runOnce(trigger, this::measureUsedPercent);
    }

    StorageGcReport runOnce(String trigger, DoubleSupplier diskPercent) {
        if (!maintenanceLock.tryAcquire()) {
            throw new IllegalStateException("a backup is running — retry when it finishes");
        }
        if (!running.compareAndSet(false, true)) {
            maintenanceLock.release();
            throw new IllegalStateException("storage GC is already running");
        }
        StorageGcReport report = new StorageGcReport(trigger);
        try {
            StoragePolicy policy = policy();
            log.info("[storage-gc] run '{}' start: disk {}% used (high {}%, low {}%)",
                    trigger, String.format("%.1f", diskPercent.getAsDouble()),
                    policy.highWatermarkPercent(), policy.lowWatermarkPercent());
            // Cheapest first: orphans (garbage references nothing) often free enough on their own.
            sweepOrphans(report);
            double percent = diskPercent.getAsDouble();
            if (percent <= policy.highWatermarkPercent()) {
                report.outcome = "below high watermark — nothing to purge";
            } else {
                purgeBelowLowWatermark(policy, report, diskPercent, percent);
            }
            vacuumAfterPurge(report);
            report.finalUsedPercent = diskPercent.getAsDouble();
            report.finishedAt = Instant.now();
            log.info("[storage-gc] run '{}' done: {} — swept {} orphan(s) ({} bytes), purged {} message(s) / {} object(s), disk now {}%",
                    trigger, report.outcome, report.sweptOrphanObjects, report.sweptBytes,
                    report.purgedMessages, report.purgedAttachmentObjects,
                    String.format("%.1f", report.finalUsedPercent));
        } catch (RuntimeException error) {
            report.outcome = "failed: " + error.getMessage();
            report.finishedAt = Instant.now();
            log.warn("[storage-gc] run '{}' failed: {}", trigger, error.getMessage(), error);
        } finally {
            running.set(false);
            lastReport = report;
            maintenanceLock.release();
        }
        return report;
    }

    /** Pre-checks (synchronous, so the controller answers a clean 409 instead of a thread
     *  dying silently) and runs one pass on a daemon thread — the 202 pattern shared with the
     *  backup run-now. */
    public void startManualRun() {
        if (running.get()) {
            throw new IllegalStateException("a cleanup is already running");
        }
        if (maintenanceLock.isLocked()) {
            throw new IllegalStateException("a backup is running — retry when it finishes");
        }
        Thread runner = new Thread(() -> {
            try {
                runOnce("manual");
            } catch (IllegalStateException raced) {
                // Lost the lock/CAS race between the pre-check and the thread start — rare, logged.
                log.warn("[storage-gc] run lost the race and did not start: {}", raced.getMessage());
            }
        }, "oak-storage-gc");
        runner.setDaemon(true);
        runner.start();
    }

    /** MinIO objects under attachments/ referenced by NO message — aborted uploads, objects of
     *  deleted messages from before the event tier existed. Younger than the grace they are left
     *  alone: an upload may still be in flight to its message. */
    private void sweepOrphans(StorageGcReport report) {
        Instant cutoff = Instant.now().minus(sweepGrace);
        for (AttachmentObjectStore.StoredObject object : objectStore.listAttachmentObjects()) {
            if (object.lastModified().isAfter(cutoff)) {
                continue;
            }
            if (!messageService.attachmentExists(object.key())) {
                objectStore.delete(object.key());
                report.sweptOrphanObjects += 1;
                report.sweptBytes += object.sizeBytes();
            }
        }
        // The emoji catalog's own pass: the prefix is invisible to the listing above, and the
        // only leak is an ABANDONED UPLOAD SLOT (key minted by /api/emojis/slot, row never
        // created) — the grace covers the slot→PUT→create flight the same way it covers an
        // upload in flight to its message. Same counters; no report-shape change.
        for (AttachmentObjectStore.StoredObject object : objectStore.listCustomEmojiObjects()) {
            if (object.lastModified().isAfter(cutoff)) {
                continue;
            }
            if (!emojiRepository.existsByFileKey(object.key())) {
                objectStore.delete(object.key());
                report.sweptOrphanObjects += 1;
                report.sweptBytes += object.sizeBytes();
            }
        }
        // The sticker catalog's pass — the identical abandoned-slot story against the
        // sticker table. Message-held copies of a DELETED sticker's key sweep with the
        // object (catalog row gone → not exists): those messages render tombstones, the
        // accepted emoji-style tradeoff.
        for (AttachmentObjectStore.StoredObject object : objectStore.listStickerObjects()) {
            if (object.lastModified().isAfter(cutoff)) {
                continue;
            }
            if (!stickerRepository.existsByFileKey(object.key())) {
                objectStore.delete(object.key());
                report.sweptOrphanObjects += 1;
                report.sweptBytes += object.sizeBytes();
            }
        }
        if (report.sweptOrphanObjects > 0) {
            log.info("[storage-gc] swept {} orphan object(s), {} bytes", report.sweptOrphanObjects, report.sweptBytes);
        }
    }

    /**
     * Batch-deletes the OLDEST messages until the disk percent is at/below the low watermark.
     * Stops on: the min-keep floor (the newest messages are never purged), an empty table, or
     * {@value #NO_PROGRESS_BATCHES} consecutive batches with no disk improvement — the Postgres
     * signature (DELETE frees no OS space; only VACUUM FULL does).
     */
    private void purgeBelowLowWatermark(StoragePolicy policy, StorageGcReport report,
                                        DoubleSupplier diskPercent, double percent) {
        int stagnantBatches = 0;
        report.outcome = "low watermark reached";
        while (percent > policy.lowWatermarkPercent()) {
            long remaining = messageRepository.count() - minKeepMessages;
            if (remaining <= 0) {
                report.outcome = "min-keep floor reached — more disk needed than history can give";
                break;
            }
            // A batch never crosses the min-keep floor: the newest history is untouchable.
            List<Long> batch = messageRepository.findIdsOldestFirst(
                    PageRequest.of(0, (int) Math.min(PURGE_BATCH_SIZE, remaining)));
            if (batch.isEmpty()) {
                report.outcome = "no messages left to purge";
                break;
            }
            purgeBatch(batch, report);
            double next = diskPercent.getAsDouble();
            if (next >= percent - 0.01) {
                stagnantBatches += 1;
                if (stagnantBatches >= NO_PROGRESS_BATCHES) {
                    report.outcome = "stopped: purging does not free disk space (Postgres bloat) — "
                            + "VACUUM FULL messages manually if the table is large";
                    log.warn("[storage-gc] {}", report.outcome);
                    break;
                }
            } else {
                stagnantBatches = 0;
            }
            percent = next;
        }
    }

    /** One transaction: reactions (FK) → rows → objects orphaned by THIS batch. */
    private void purgeBatch(List<Long> ids, StorageGcReport report) {
        Set<String> keys = new HashSet<>();
        Long purged = transactionTemplate.execute(status -> {
            List<Message> messages = messageRepository.findAllById(ids);
            for (Message message : messages) {
                for (AttachmentRef reference : attachmentListCodec.read(message.getAttachments())) {
                    if (reference.key() != null) {
                        keys.add(reference.key());
                    }
                    if (reference.thumbnailKey() != null) {
                        keys.add(reference.thumbnailKey());
                    }
                }
            }
            reactionRepository.deleteByMessageIdIn(ids);
            messageRepository.deleteAll(messages);
            messageRepository.flush();
            return (long) messages.size();
        });
        report.purgedMessages += purged == null ? 0 : purged;
        // AFTER the rows are gone: a key with no remaining reference (a forward keeps its object)
        // dies with this batch — the event tier, applied to the GC's own deletions.
        for (String key : keys) {
            if (!messageService.attachmentExists(key)) {
                objectStore.delete(key);
                report.purgedAttachmentObjects += 1;
            }
        }
    }

    /** Returns the table's space to the OS so the disk percent can actually drop. Only Postgres
     *  (H2 has no VACUUM); {@code lock_timeout} makes a busy table defer instead of queueing. */
    private void vacuumAfterPurge(StorageGcReport report) {
        if (!vacuumFull || report.purgedMessages == 0) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
                return;
            }
            connection.setAutoCommit(true);
            long startedAt = System.currentTimeMillis();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET lock_timeout TO '5s'");
                statement.execute("VACUUM FULL messages");
            }
            log.info("[storage-gc] VACUUM FULL messages took {} ms", System.currentTimeMillis() - startedAt);
        } catch (Exception error) {
            log.warn("[storage-gc] VACUUM FULL skipped: {}", error.getMessage());
        }
    }

    private double measureUsedPercent() {
        return usage().usedPercent();
    }

    /**
     * The nightly trigger. A fixed tick compares "now" against the configured minute-of-day: the
     * run fires on the first tick AT-OR-AFTER the configured time inside a CATCH-UP window of
     * several ticks ({@code lastScheduledRunDate} latches the day AFTER a successful start),
     * which keeps the time live-editable from the settings screen without rescheduling anything
     * — and lets a backup that holds the maintenance lock at the exact minute simply DEFER the
     * nightly pass instead of silently skipping it for the whole day.
     */
    @Scheduled(fixedDelayString = "${oak.storage.gc-tick-interval:PT10M}")
    public void scheduledTick() {
        try {
            StoragePolicy policy = policy();
            if (!policy.gcEnabled()) {
                return; // automatic cleanup is off — only a manual "run now" may purge
            }
            int nowMinute = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
            long windowMinutes = Math.max(1, tickInterval.toMinutes()) * CATCHUP_TICKS;
            boolean dueMinute = nowMinute >= policy.gcMinuteOfDay()
                    && nowMinute - policy.gcMinuteOfDay() < windowMinutes;
            if (dueMinute && !LocalDate.now().equals(lastScheduledRunDate)) {
                try {
                    runOnce("scheduled " + LocalDate.now());
                    lastScheduledRunDate = LocalDate.now();
                } catch (IllegalStateException deferred) {
                    log.info("[storage-gc] scheduled run deferred: {}", deferred.getMessage());
                }
            }
        } catch (RuntimeException error) {
            log.warn("[storage-gc] tick failed: {}", error.getMessage());
        }
    }

    /** One GC pass, for the status screen (mutable: filled in as the run progresses). */
    public static final class StorageGcReport {
        public final Instant startedAt = Instant.now();
        public final String trigger;
        public volatile Instant finishedAt;
        public volatile String outcome = "";
        public volatile long sweptOrphanObjects = 0;
        public volatile long sweptBytes = 0;
        public volatile long purgedMessages = 0;
        public volatile long purgedAttachmentObjects = 0;
        public volatile double finalUsedPercent = -1;

        public StorageGcReport(String trigger) {
            this.trigger = trigger;
        }
    }
}
