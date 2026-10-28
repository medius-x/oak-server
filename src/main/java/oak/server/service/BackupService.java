package oak.server.service;

import lombok.extern.slf4j.Slf4j;
import oak.server.repository.ServerProfileRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the full-instance backup archive: every chat/message/roster row plus every stored
 * object, as one zip the owner downloads to their machine (the "Back up now" of the Server
 * settings tab and the source of a migration). The server deliberately keeps NO schedule and
 * NO retention of its own — the periodic backup is a CLIENT-side concern; this service only
 * runs a pass when asked.
 *
 * <p><b>Archive layout:</b> {@code manifest.json} (format/Flyway/app versions, row counts),
 * {@code data.sql.gz} (a self-sufficient restore script — see {@link BackupDumpSql}) and
 * {@code objects/<minio key>} for every object of the bucket (all six prefixes: attachments,
 * avatars, avatars/originals, server-avatars, custom-emojis, stickers). Entries are STORED
 * (deflate level 0) — media is already compressed, and the SQL carries its own gzip layer.
 *
 * <p><b>Consistency:</b> all table SELECTs run on ONE connection in REPEATABLE READ — a single
 * database snapshot; the {@link StorageMaintenanceLock} keeps the GC from deleting
 * messages/objects mid-dump. An object that fails to read is SKIPPED (counted as
 * {@code failedObjects}, warned) — an archive missing one attachment beats no archive.
 *
 * <p><b>Single slot:</b> the finished zip replaces {@code oak-backup.zip} atomically (write to
 * {@code .tmp}, move) in {@code oak.backup.dir} (default: a temp directory — the server stores
 * nothing beyond the latest archive, per the no-retention decision).
 */
@Slf4j
@Service
public class BackupService {

    /** Every table the dump covers, in a stable order. {@code refresh_tokens} is EXCLUDED on
     *  purpose: migration to a new machine means a new JWT secret (new .env) and an honest
     *  re-login for everyone; {@code flyway_schema_history} is excluded because the restoring
     *  server has already created the exact same schema with its own Flyway run. */
    static final List<String> BACKUP_TABLES = List.of(
            "server_profile", "roles", "users", "user_roles", "channels", "messages",
            "message_reactions", "channel_participants", "channel_read_states",
            "channel_role_overrides", "channel_user_overrides", "relationships",
            "discussions", "invites", "custom_emojis", "sticker_packs", "stickers",
            "ownership_transfer");

    private final DataSource dataSource;
    private final AttachmentObjectStore objectStore;
    private final ServerProfileRepository profileRepository;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final StorageMaintenanceLock maintenanceLock;
    private final ObjectMapper objectMapper;
    private final File backupDir;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile BackupReport lastReport = null;

    public BackupService(DataSource dataSource,
                         AttachmentObjectStore objectStore,
                         ServerProfileRepository profileRepository,
                         ObjectProvider<BuildProperties> buildProperties,
                         StorageMaintenanceLock maintenanceLock,
                         ObjectMapper objectMapper,
                         @Value("${oak.backup.dir:}") String backupDirSetting) {
        this.dataSource = dataSource;
        this.objectStore = objectStore;
        this.profileRepository = profileRepository;
        this.buildProperties = buildProperties;
        this.maintenanceLock = maintenanceLock;
        this.objectMapper = objectMapper;
        this.backupDir = backupDirSetting.isBlank()
                ? new File(System.getProperty("java.io.tmpdir"), "oak-backup")
                : new File(backupDirSetting);
    }

    /** The finished (or in-progress-target) archive file; may not exist yet. */
    public File backupFile() {
        return new File(backupDir, "oak-backup.zip");
    }

    public boolean isRunning() {
        return running.get();
    }

    public BackupReport lastReport() {
        return lastReport;
    }

    /** Quick size preview for the "Back up now?" confirmation dialog: the exact sum of the
     *  bucket listing (metadata only — no bytes read) plus an UPPER estimate of the SQL dump
     *  from {@code pg_relation_size} of every public table (indexes excluded, gzip will
     *  shrink it further). */
    public BackupEstimate estimate() {
        long objectBytes = 0;
        long objectCount = 0;
        for (AttachmentObjectStore.StoredObject object : objectStore.listAllObjects()) {
            objectBytes += object.sizeBytes();
            objectCount += 1;
        }
        return new BackupEstimate(objectBytes, objectCount, databaseSizeBytes());
    }

    /** Pre-checks (synchronous, so the controller can answer a clean 409) and starts the pass
     *  on a daemon thread — the 202 pattern of the GC run-now. */
    public void startRun() {
        if (running.get()) {
            throw new IllegalStateException("a backup is already running");
        }
        if (maintenanceLock.isLocked()) {
            throw new IllegalStateException("a storage cleanup is running — retry when it finishes");
        }
        Thread runner = new Thread(() -> {
            try {
                runOnce();
            } catch (IllegalStateException raced) {
                // Lost the lock/CAS race between the pre-check and the thread start — rare, logged.
                log.warn("[backup] run lost the race and did not start: {}", raced.getMessage());
            }
        }, "oak-backup");
        runner.setDaemon(true);
        runner.start();
    }

    /**
     * One full backup pass. Returns the report (also latched as {@link #lastReport()}). Throws
     * {@link IllegalStateException} when the maintenance lock is held by the GC (the caller
     * decides: 409 for a manual run, defer-and-retry for a schedule).
     */
    public BackupReport runOnce() {
        if (!maintenanceLock.tryAcquire()) {
            throw new IllegalStateException("a storage cleanup is running — retry when it finishes");
        }
        if (!running.compareAndSet(false, true)) {
            maintenanceLock.release();
            throw new IllegalStateException("a backup is already running");
        }
        BackupReport report = new BackupReport();
        try {
            Files.createDirectories(backupDir.toPath());
            File target = backupFile();
            File temp = new File(backupDir, "oak-backup.zip.tmp");
            long objectCount;
            long objectBytes;
            long failedObjects;
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
                // STORED-equivalent: media payloads are already compressed, and data.sql.gz brings
                // its own gzip layer — a deflate pass would only burn CPU for nothing.
                zip.setLevel(Deflater.NO_COMPRESSION);

                zip.putNextEntry(new ZipEntry("data.sql.gz"));
                try (Connection connection = dataSource.getConnection()) {
                    writeDumpSql(connection, zip, report);
                }
                zip.closeEntry();

                long[] counts = writeObjects(zip);
                objectCount = counts[0];
                objectBytes = counts[1];
                failedObjects = counts[2];

                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(objectMapper.writeValueAsBytes(manifest(report, objectCount, objectBytes, failedObjects)));
                zip.closeEntry();
            }
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            report.sizeBytes = target.length();
            report.objectCount = objectCount;
            report.objectBytes = objectBytes;
            report.failedObjects = failedObjects;
            report.flywayVersion = flywayVersion();
            report.appVersion = appVersion();
            report.finishedAt = Instant.now();
            log.info("[backup] done: {} bytes, {} row(s) over {} table(s), {} object(s) ({} bytes, {} failed)",
                    report.sizeBytes, report.totalRows(), report.tableRows.size(),
                    objectCount, objectBytes, failedObjects);
        } catch (Exception error) {
            report.error = error.getMessage();
            report.finishedAt = Instant.now();
            log.warn("[backup] failed: {}", error.getMessage(), error);
        } finally {
            running.set(false);
            lastReport = report;
            maintenanceLock.release();
        }
        return report;
    }

    /** Every object of the bucket as {@code objects/<key>}; a read failure skips THAT object
     *  (warned, counted) — the zip entry may end up truncated, the restore runner skips
     *  unreadable entries the same forgiving way. */
    private long[] writeObjects(ZipOutputStream zip) throws IOException {
        long objectCount = 0;
        long objectBytes = 0;
        long failedObjects = 0;
        for (AttachmentObjectStore.StoredObject object : objectStore.listAllObjects()) {
            try (InputStream body = objectStore.openObject(object.key())) {
                zip.putNextEntry(new ZipEntry("objects/" + object.key()));
                body.transferTo(zip);
                zip.closeEntry();
                objectCount += 1;
                objectBytes += object.sizeBytes();
            } catch (Exception readError) {
                failedObjects += 1;
                log.warn("[backup] object '{}' skipped: {}", object.key(), readError.getMessage());
                closeEntryQuietly(zip);
            }
        }
        return new long[]{objectCount, objectBytes, failedObjects};
    }

    private static void closeEntryQuietly(ZipOutputStream zip) {
        try {
            zip.closeEntry();
        } catch (IOException closeError) {
            // Nothing to salvage — the next putNextEntry would fail the whole archive; give up loudly.
            throw new IllegalStateException("backup zip became unwritable: " + closeError.getMessage(), closeError);
        }
    }

    private void writeDumpSql(Connection connection, ZipOutputStream zip, BackupReport report)
            throws SQLException, IOException {
        // One connection, one REPEATABLE READ snapshot for every table — a consistent dump.
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        } catch (SQLException unsupported) {
            // H2 does not support every isolation level switch; tests only generate the text.
        }
        connection.setAutoCommit(false);
        // The gzip wrapper NEVER closes the zip stream underneath (close() is overridden to
        // finish()), so the try-with-resources writer below flushes the trailer correctly.
        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(new GzipFinishOnlyStream(zip), StandardCharsets.UTF_8))) {
            writer.write("-- oak-server backup data (restore script)\n");
            writer.write(BackupDumpSql.replicationRolePreamble());
            writer.write(BackupDumpSql.truncateStatement(BACKUP_TABLES));
            for (String table : BACKUP_TABLES) {
                long rows = dumpTable(connection, table, writer);
                report.tableRows.put(table, rows);
            }
            writer.write(BackupDumpSql.replicationRoleEpilogue());
        } finally {
            connection.commit();
            connection.setAutoCommit(true);
        }
    }

    private long dumpTable(Connection connection, String table, Writer writer)
            throws SQLException, IOException {
        long rows = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData meta = result.getMetaData();
            int columnCount = meta.getColumnCount();
            List<String> columns = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                columns.add(meta.getColumnName(index));
            }
            while (result.next()) {
                List<Object> values = new ArrayList<>(columnCount);
                for (int index = 1; index <= columnCount; index++) {
                    values.add(result.getObject(index));
                }
                writer.write(BackupDumpSql.insertStatement(table, columns, values));
                rows += 1;
            }
            // user_roles (join table) has no id column and no sequence of its own — nothing to re-sync.
            if (columns.contains("id")) {
                writer.write(BackupDumpSql.setvalStatement(table));
            }
        }
        return rows;
    }

    private Map<String, Object> manifest(BackupReport report, long objectCount,
                                         long objectBytes, long failedObjects) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", 1);
        manifest.put("flywayVersion", flywayVersion());
        manifest.put("appVersion", appVersion());
        manifest.put("serverName", profileRepository.singleton().getName());
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("tableRows", report.tableRows);
        manifest.put("objectCount", objectCount);
        manifest.put("objectBytes", objectBytes);
        manifest.put("failedObjects", failedObjects);
        return manifest;
    }

    /** The last SUCCESSFULLY applied migration — by installed rank, not by string max ("9"
     *  would beat "17" lexicographically). Null when Flyway is off (test profile). Static:
     *  {@link RestoreRunner} asks the same question of the freshly migrated database. */
    static String flywayVersion(DataSource source) {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success = TRUE"
                             + " ORDER BY installed_rank DESC LIMIT 1")) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException noHistory) {
            return null;
        }
    }

    private String flywayVersion() {
        return flywayVersion(dataSource);
    }

    private String appVersion() {
        BuildProperties properties = buildProperties.getIfAvailable();
        return properties != null ? properties.getVersion() : "dev";
    }

    /** Upper bound of the dump's SQL size: every public table's heap size, indexes excluded
     *  (they are not dumped). Postgres-only — H2 has no pg_catalog, tests get 0. */
    private long databaseSizeBytes() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COALESCE(SUM(pg_relation_size(c.oid)), 0)"
                             + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                             + " WHERE n.nspname = 'public' AND c.relkind = 'r'")) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (SQLException notPostgres) {
            return 0;
        }
    }

    private static final class GzipFinishOnlyStream extends java.util.zip.GZIPOutputStream {
        private GzipFinishOnlyStream(OutputStream target) throws IOException {
            super(target);
        }

        @Override
        public void close() throws IOException {
            // Write the gzip trailer but keep the zip stream open for the next entry.
            finish();
        }
    }

    /** One backup pass, for the status screen (mutable: filled in as the run progresses). */
    public static final class BackupReport {
        public final Instant startedAt = Instant.now();
        public final Map<String, Long> tableRows = new LinkedHashMap<>();
        public volatile Instant finishedAt;
        public volatile long sizeBytes;
        public volatile long objectCount;
        public volatile long objectBytes;
        public volatile long failedObjects;
        public volatile String flywayVersion;
        public volatile String appVersion;
        public volatile String error;

        public long totalRows() {
            return tableRows.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** The estimate payload: exact object bytes/count plus the SQL upper bound. */
    public record BackupEstimate(long objectBytes, long objectCount, long approxDatabaseBytes) {}
}
