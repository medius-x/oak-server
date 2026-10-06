package oak.server.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Tails the docker-compose peripheral containers (postgres, minio, janus, coturn) through the
 * host's {@code docker} CLI — the logging round (2026-10-06). The containers' json-file logs
 * live inside the Docker VM on Windows and are unreachable from the filesystem, but the server
 * runs on the SAME host as the CLI, so {@code docker logs --tail N} is the one clean path
 * (mounting the docker socket would be a hole we do not want).
 *
 * Every failure degrades to a text note ("unavailable: …") inside the archive instead of
 * failing the download: a host without Docker still gets its server logs. Resolution accepts
 * both a plain container name and compose-style names ({@code env-postgres-1}).
 */
@Service
public class ContainerLogCollector {

    /** Test seam: runs one command, returns its stdout. The real impl spawns a process. */
    public interface CommandRunner {
        String run(Duration timeout, String... command) throws IOException;
    }

    private final boolean enabled;
    private final List<String> containers;
    private final int tailLines;
    private final Duration timeout;
    private final CommandRunner runner;

    @Autowired
    public ContainerLogCollector(@Value("${oak.logging.container-logs.enabled:true}") boolean enabled,
                                 @Value("${oak.logging.container-logs.containers:postgres,minio,janus,coturn}") String containers,
                                 @Value("${oak.logging.container-logs.tail-lines:2000}") int tailLines,
                                 @Value("${oak.logging.container-logs.timeout:PT10S}") Duration timeout) {
        this(enabled, containers, tailLines, timeout, ContainerLogCollector::runProcess);
    }

    /** Test seam: injects a fake runner (the tests live outside this package). */
    public ContainerLogCollector(boolean enabled, String containers, int tailLines, Duration timeout,
                                 CommandRunner runner) {
        this.enabled = enabled;
        this.containers = Arrays.stream(containers.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.tailLines = tailLines;
        this.timeout = timeout;
        this.runner = runner;
    }

    /** Configured container name → its log tail (or an "unavailable: …" note). */
    public Map<String, String> collect() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!enabled) {
            return out;
        }
        for (String name : containers) {
            out.put(name, collectOne(name));
        }
        return out;
    }

    private String collectOne(String name) {
        try {
            String listing = runner.run(timeout, "docker", "ps", "--format", "{{.Names}}");
            String container = resolveContainer(listing, name);
            if (container == null) {
                return "unavailable: no running container matches \"" + name + "\"";
            }
            return runner.run(timeout, "docker", "logs", "--timestamps", "--tail", String.valueOf(tailLines), container);
        } catch (IOException | RuntimeException ex) {
            return "unavailable: " + ex.getMessage();
        }
    }

    /** Exact name or a compose-style {@code <project>-<name>-<index>} suffix match. */
    public static String resolveContainer(String psOutput, String name) {
        for (String rawLine : psOutput.split("\n")) {
            String candidate = rawLine.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.equals(name)
                    || candidate.matches(".*-" + Pattern.quote(name) + "-\\d+$")) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Reads the command's stdout fully (up to EOF), guarded by a killer thread: a hung command
     * (or one whose output nobody drains) is destroyed at the timeout instead of deadlocking on
     * a full OS pipe. stderr is discarded — the exit code carries the failure.
     */
    static String runProcess(Duration timeout, String... command) throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(timeout.toMillis());
            } catch (InterruptedException earlyFinish) {
                return;
            }
            process.destroyForcibly();
        }, "oak-container-logs-killer");
        killer.setDaemon(true);
        killer.start();
        try {
            byte[] output = process.getInputStream().readAllBytes(); // EOF when the process dies
            if (!process.waitFor(timeout.toMillis() * 2, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("command timed out: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new IOException("command failed with exit code " + process.exitValue()
                        + ": " + String.join(" ", command));
            }
            return new String(output, StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running: " + command[0], interrupted);
        } finally {
            killer.interrupt();
        }
    }
}
