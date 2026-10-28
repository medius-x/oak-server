package oak.server.service;

import lombok.extern.slf4j.Slf4j;
import oak.server.entity.ServerProfile;
import oak.server.repository.ServerProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One-shot restore-from-backup at boot (the provisioning "restore" leg, backup round
 * 2026-10-28): when {@code oak.restore.file} points at an archive built by
 * {@link BackupService}, this runner loads it into the FRESHLY MIGRATED database before any
 * seeder runs — the migrated machine comes up with every conversation, account and stored
 * object of the original, "as if it had all been created there". Runs BEFORE the
 * {@code RoleSeeder} (order -100), whose idempotent repairs then simply pass over the
 * restored rows.
 *
 * <p><b>Version gate:</b> the manifest's Flyway version must EQUAL the current one. A
 * mismatch means the INSERTs reference a different shape than the schema Flyway just
 * created (the V16 retired-bits trap is documented in {@code Permission}) — the boot fails
 * loudly instead of loading half-valid data. The provisioning pipeline deploys the SAME
 * server version as the client that took the backup, so in practice this always holds.
 *
 * <p><b>Markers:</b> the log carries {@code RESTORE COMPLETED} / {@code RESTORE FAILED: …} —
 * the provisioning step greps the container log for exactly these (a failed context behind
 * Caddy would otherwise look like an eternal 502 wait-timeout). On success the archive is
 * renamed to {@code *.done} so a restart never replays it; on failure it stays in place
 * (fix the cause, restart, retry).
 *
 * <p><b>Post-restore address patch:</b> when {@code oak.restore.public-host} is set (the
 * provisioning knows the new machine's address), {@code server_profile.connection_*} is
 * rewritten AFTER the data load — invite links advertise the new machine with no REST token
 * at hand. The restored accounts keep their passwords but everyone re-logs in by design:
 * the new {@code .env} mints a fresh JWT secret and {@code refresh_tokens} is not migrated.
 */
@Slf4j
@Component
@Order(-100)
public class RestoreRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final AttachmentObjectStore objectStore;
    private final ServerProfileRepository profileRepository;
    private final ObjectMapper objectMapper;
    private final String restoreFile;
    private final String publicHost;
    private final int publicPort;
    private final String publicScheme;

    public RestoreRunner(DataSource dataSource,
                         AttachmentObjectStore objectStore,
                         ServerProfileRepository profileRepository,
                         ObjectMapper objectMapper,
                         @Value("${oak.restore.file:}") String restoreFile,
                         @Value("${oak.restore.public-host:}") String publicHost,
                         @Value("${oak.restore.public-port:443}") int publicPort,
                         @Value("${oak.restore.public-scheme:https}") String publicScheme) {
        this.dataSource = dataSource;
        this.objectStore = objectStore;
        this.profileRepository = profileRepository;
        this.objectMapper = objectMapper;
        this.restoreFile = restoreFile;
        this.publicHost = publicHost;
        this.publicPort = publicPort;
        this.publicScheme = publicScheme;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (restoreFile.isBlank()) {
            return; // normal boot — no restore requested
        }
        Path archive = Path.of(restoreFile);
        if (!Files.isRegularFile(archive)) {
            log.warn("[restore] oak.restore.file is set but '{}' does not exist — skipping", restoreFile);
            return;
        }
        try {
            RestoreOutcome outcome = restore(archive);
            log.info("[restore] RESTORE COMPLETED — {} statement(s), {} object(s){}",
                    outcome.statements(), outcome.objects(),
                    publicHost.isBlank() ? "" : ", advertised address rewritten");
        } catch (Exception error) {
            log.error("[restore] RESTORE FAILED: {}", error.getMessage(), error);
            throw new IllegalStateException("Restore failed: " + error.getMessage(), error);
        }
    }

    private RestoreOutcome restore(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            String manifestVersion = manifestFlywayVersion(zip);
            String currentVersion = BackupService.flywayVersion(dataSource);
            if (manifestVersion == null || manifestVersion.isBlank()) {
                throw new IOException("the archive manifest carries no Flyway version");
            }
            if (!manifestVersion.equals(currentVersion)) {
                throw new IOException("backup schema version '" + manifestVersion
                        + "' does not match this server's schema version '" + currentVersion
                        + "' — take the backup with the same server version");
            }
            int statements = executeDataScript(zip);
            int objects = restoreObjects(zip);
            patchAdvertisedAddress();
            Files.move(archive, archive.resolveSibling(archive.getFileName().toString() + ".done"));
            return new RestoreOutcome(statements, objects);
        }
    }

    private String manifestFlywayVersion(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("manifest.json");
        if (entry == null) {
            throw new IOException("the archive has no manifest.json entry — not an oak backup");
        }
        try (InputStream body = zip.getInputStream(entry)) {
            Map<?, ?> manifest = objectMapper.readValue(body, Map.class);
            Object version = manifest.get("flywayVersion");
            return version == null ? null : version.toString();
        }
    }

    /** Executes the whole restore script in ONE transaction: either the machine comes up
     *  fully restored or the database stays as the seeders left it. */
    private int executeDataScript(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("data.sql.gz");
        if (entry == null) {
            throw new IOException("the archive has no data.sql.gz entry");
        }
        int[] executedRef = {0};
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (InputStream compressed = zip.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         new GZIPInputStream(compressed), StandardCharsets.UTF_8));
                 Statement statement = connection.createStatement()) {
                BackupDumpSql.splitSqlStatements(reader, sql -> {
                    try {
                        statement.execute(sql);
                        executedRef[0] += 1;
                    } catch (SQLException error) {
                        throw new IllegalStateException("restore statement failed ("
                                + sql.substring(0, Math.min(80, sql.length())) + "...): " + error.getMessage(), error);
                    }
                });
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure instanceof IOException ioException
                        ? ioException
                        : new IOException("restore script failed: " + failure.getMessage(), failure);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException pool) {
            throw new IOException("could not open a database connection: " + pool.getMessage(), pool);
        }
        return executedRef[0];
    }

    /** Puts every {@code objects/<key>} entry back into the bucket, FORGIVINGLY: an
     *  unreadable entry (the backup side skips a dying object the same way) must not fail
     *  the whole machine restore. */
    private int restoreObjects(ZipFile zip) throws IOException {
        int restored = 0;
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith("objects/")) {
                continue;
            }
            String key = name.substring("objects/".length());
            try (InputStream body = zip.getInputStream(entry)) {
                objectStore.putObject(key, body, entry.getSize());
                restored += 1;
            } catch (Exception objectError) {
                log.warn("[restore] object '{}' skipped: {}", key, objectError.getMessage());
            }
        }
        return restored;
    }

    private void patchAdvertisedAddress() {
        if (publicHost.isBlank()) {
            return; // not a provisioning restore — the restored address stays as it was
        }
        ServerProfile profile = profileRepository.singleton();
        profile.setConnectionHost(publicHost);
        profile.setConnectionPort(publicPort);
        profile.setConnectionScheme(publicScheme);
        profileRepository.save(profile);
    }

    private record RestoreOutcome(int statements, int objects) {}
}
