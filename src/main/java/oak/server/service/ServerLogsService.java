package oak.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles the downloadable server-logs archive (logging round, 2026-10-06): the active
 * {@code oak-server.log} plus the last-24h rolled {@code .gz} files (logback retention keeps
 * the set bounded; here a size cap guards the request anyway), the container tails from
 * {@link ContainerLogCollector}, and a manifest listing what made it in and what was skipped.
 * Rolled files are UNPACKED in the archive — one zip opens and reads end to end.
 */
@Slf4j
@Service
public class ServerLogsService {

    /** Server-log bytes the archive carries at most; older files are skipped (noted in the manifest). */
    static final long MAX_SERVER_LOG_BYTES = 48L * 1024 * 1024;

    private final ContainerLogCollector containerLogCollector;
    private final Path logsDirectory;

    public ServerLogsService(ContainerLogCollector containerLogCollector,
                             @Value("${oak.logging.directory:}") String configuredDirectory) {
        this.containerLogCollector = containerLogCollector;
        this.logsDirectory = configuredDirectory.isBlank()
                ? Path.of(System.getProperty("user.home"), ".oak-server")
                : Path.of(configuredDirectory);
    }

    public byte[] buildZip() {
        List<String> manifest = new ArrayList<>();
        manifest.add("generatedAt: " + LocalDateTime.now());
        manifest.add("host: " + hostName());
        manifest.add("os: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        manifest.add("java: " + System.getProperty("java.version"));
        manifest.add("uptime: " + Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            long budget = MAX_SERVER_LOG_BYTES;
            for (ServerLogFile logFile : listServerLogFiles()) {
                byte[] content = logFile.readContent();
                if (content.length > budget) {
                    manifest.add("skipped server/" + logFile.fileName()
                            + " (" + content.length + " bytes — archive size cap)");
                    continue;
                }
                budget -= content.length;
                writeEntry(zip, "server/" + logFile.fileName(), content);
                manifest.add("server/" + logFile.fileName() + " (" + content.length + " bytes)");
            }
            for (Map.Entry<String, String> container : containerLogCollector.collect().entrySet()) {
                String note = container.getValue();
                String entryName = "containers/" + container.getKey().replaceAll("[^a-zA-Z0-9_.-]", "_")
                        + (note.startsWith("unavailable:") ? ".txt" : ".log");
                writeEntry(zip, entryName, note.getBytes(StandardCharsets.UTF_8));
                manifest.add(entryName);
            }
            writeEntry(zip, "manifest.txt", String.join("\n", manifest).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to assemble the server logs archive", ex);
        }
        log.info("[logs] server logs archive assembled: {} manifest lines, {} bytes", manifest.size(), buffer.size());
        return buffer.toByteArray();
    }

    /** The active log and the rolled {@code .gz} files, oldest first (chronological reading order). */
    private List<ServerLogFile> listServerLogFiles() {
        if (!Files.isDirectory(logsDirectory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(logsDirectory)) {
            return entries
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equals("oak-server.log")
                                || (name.startsWith("oak-server.") && name.endsWith(".log.gz"));
                    })
                    .sorted(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .map(ServerLogFile::new)
                    .toList();
        } catch (IOException ex) {
            log.warn("[logs] cannot list {} — the archive will carry containers only: {}", logsDirectory, ex.getMessage());
            return List.of();
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException ex) {
            return "unknown";
        }
    }

    private record ServerLogFile(Path path) {
        private String fileName() {
            return path.getFileName().toString();
        }

        private byte[] readContent() {
            try {
                if (fileName().endsWith(".gz")) {
                    try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(path))) {
                        return gzip.readAllBytes();
                    }
                }
                return Files.readAllBytes(path);
            } catch (IOException ex) {
                // A rolled file can vanish between listing and reading (retention cleaner) —
                // degrade to a note instead of failing the whole archive.
                return ("unavailable: " + ex.getMessage()).getBytes(StandardCharsets.UTF_8);
            }
        }
    }
}
