package oak.server.service;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import oak.server.config.JanusProperties;
import oak.server.config.MinioProperties;
import oak.server.config.TurnProperties;
import oak.server.dto.ServerHealthResponse;
import oak.server.dto.ServerHealthResponse.ContainerCheck;
import oak.server.dto.ServerHealthResponse.Entry;
import oak.server.dto.ServerHealthResponse.ServiceCheck;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Collects the health of the four backing integrations for {@code GET /api/server/health}
 * (Server Settings → Server, healthcheck round 2026-10-07). Two facets per service:
 * <ul>
 *   <li><b>Service probe</b> — run by oak-server itself, so it answers the real question
 *       ("can THIS server use the integration?") even when the service runs outside docker:
 *       a JDBC liveness check, a MinIO {@code bucketExists}, a Janus admin
 *       {@code list_tokens} and a TCP connect to the TURN port. Probes run in parallel and
 *       each is bounded ({@link #PROBE_BUDGET}) — a wedged backend degrades its own row to
 *       TIMEOUT instead of stalling the endpoint past the client's request timeout.</li>
 *   <li><b>Container facet</b> — one {@code docker ps -a} snapshot (stopped containers are
 *       the headline diagnosis), name resolution shared with {@link ContainerLogCollector}.
 *       Docker missing/hung degrades every row to UNKNOWN, never fails the response; a
 *       DISABLED facet (the prod profile) omits the container side entirely (null).</li>
 * </ul>
 * The probes deliberately use the server's OWN configured clients/addresses (datasource,
 * MinIO SDK, Janus admin client, TURN host/port) — the health of what oak-server actually
 * talks to, not a parallel set of addresses that could drift from the real wiring.
 */
@Slf4j
@Service
public class ServerHealthService {

    private static final String UP = "UP";
    private static final String DOWN = "DOWN";
    private static final String DISABLED = "DISABLED";
    private static final String TIMEOUT = "TIMEOUT";
    private static final String ABSENT = "ABSENT";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String HEALTHY = "HEALTHY";
    private static final String UNHEALTHY = "UNHEALTHY";
    private static final String STARTING = "STARTING";
    private static final String NONE = "NONE";

    /** Per-probe ceiling; the four probes run concurrently, so this is also the overall budget. */
    private static final Duration PROBE_BUDGET = Duration.ofSeconds(5);
    private static final int TURN_CONNECT_TIMEOUT_MILLIS = 2000;

    private final DataSource dataSource;
    private final ObjectProvider<MinioClient> minioProvider;
    private final MinioProperties minioProperties;
    private final ObjectProvider<JanusAdminClient> janusProvider;
    private final JanusProperties janusProperties;
    private final TurnProperties turnProperties;
    private final boolean dockerEnabled;
    private final List<String> containerNames;
    private final Duration dockerTimeout;
    private final ContainerLogCollector.CommandRunner runner;

    /** Daemon pool of probe size: one concurrent collect() runs all four probes at once. */
    private final ExecutorService probeExecutor;

    @Autowired
    public ServerHealthService(DataSource dataSource,
                               ObjectProvider<MinioClient> minioProvider,
                               MinioProperties minioProperties,
                               ObjectProvider<JanusAdminClient> janusProvider,
                               JanusProperties janusProperties,
                               TurnProperties turnProperties,
                               @Value("${oak.health.docker.enabled:true}") boolean dockerEnabled,
                               @Value("${oak.health.docker.containers:postgres,minio,janus,coturn}") String containers,
                               @Value("${oak.health.docker.timeout:PT10S}") Duration dockerTimeout) {
        this(dataSource, minioProvider, minioProperties, janusProvider, janusProperties, turnProperties,
                dockerEnabled, containers, dockerTimeout, ContainerLogCollector::runProcess);
    }

    /** Test seam: injects a fake runner (the tests live outside this package). */
    public ServerHealthService(DataSource dataSource,
                               ObjectProvider<MinioClient> minioProvider,
                               MinioProperties minioProperties,
                               ObjectProvider<JanusAdminClient> janusProvider,
                               JanusProperties janusProperties,
                               TurnProperties turnProperties,
                               boolean dockerEnabled,
                               String containers,
                               Duration dockerTimeout,
                               ContainerLogCollector.CommandRunner runner) {
        this.dataSource = dataSource;
        this.minioProvider = minioProvider;
        this.minioProperties = minioProperties;
        this.janusProvider = janusProvider;
        this.janusProperties = janusProperties;
        this.turnProperties = turnProperties;
        this.dockerEnabled = dockerEnabled;
        this.containerNames = Arrays.stream(containers.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.dockerTimeout = dockerTimeout;
        this.runner = runner;
        this.probeExecutor = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "oak-health-check");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ServerHealthResponse collect() {
        Map<String, ContainerCheck> containers = containerSnapshot();
        List<Entry> services = List.of(
                new Entry("postgres", bounded(this::probePostgres), containerOf(containers, "postgres")),
                new Entry("minio", bounded(this::probeMinio), containerOf(containers, "minio")),
                new Entry("janus", bounded(this::probeJanus), containerOf(containers, "janus")),
                new Entry("coturn", bounded(this::probeCoturn), containerOf(containers, "coturn")));
        for (Entry entry : services) {
            String status = entry.service().status();
            if (DOWN.equals(status) || TIMEOUT.equals(status)) {
                log.warn("[health] {} {}: {}", entry.name(), status, entry.service().detail());
            }
        }
        return new ServerHealthResponse(Instant.now(), services);
    }

    // --- service probes (each returns, never throws; the bounded() wrapper caps the wait) ---

    private ServiceCheck probePostgres() {
        long startNanos = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) {
                return new ServiceCheck(DOWN, latencyMillis(startNanos), "connection is not valid");
            }
            return new ServiceCheck(UP, latencyMillis(startNanos), null);
        } catch (Exception failure) {
            return new ServiceCheck(DOWN, latencyMillis(startNanos), String.valueOf(failure.getMessage()));
        }
    }

    private ServiceCheck probeMinio() {
        if (!minioProperties.enabled()) {
            return new ServiceCheck(DISABLED, null, "disabled by configuration");
        }
        MinioClient client = minioProvider.getIfAvailable();
        if (client == null) {
            return new ServiceCheck(DISABLED, null, "MinIO client is not wired");
        }
        long startNanos = System.nanoTime();
        try {
            client.bucketExists(BucketExistsArgs.builder().bucket(minioProperties.bucket()).build());
            return new ServiceCheck(UP, latencyMillis(startNanos), null);
        } catch (Exception failure) {
            return new ServiceCheck(DOWN, latencyMillis(startNanos), String.valueOf(failure.getMessage()));
        }
    }

    private ServiceCheck probeJanus() {
        if (!janusProperties.adminEnabled()) {
            return new ServiceCheck(DISABLED, null, "disabled by configuration");
        }
        JanusAdminClient client = janusProvider.getIfAvailable();
        if (client == null) {
            return new ServiceCheck(DISABLED, null, "Janus admin client is not wired");
        }
        long startNanos = System.nanoTime();
        try {
            JsonNode answer = client.listTokens();
            String janus = answer.path("janus").asText("");
            if ("success".equals(janus)) {
                return new ServiceCheck(UP, latencyMillis(startNanos), null);
            }
            // The gateway answered but refused: alive transport, broken admin relation (e.g. a
            // secret mismatch) — token minting is dead, which for voice IS an outage.
            String reason = answer.path("error").path("reason").asText("");
            String detail = reason.isEmpty() ? "gateway answered: " + janus : reason;
            return new ServiceCheck(DOWN, latencyMillis(startNanos), detail);
        } catch (Exception failure) {
            return new ServiceCheck(DOWN, latencyMillis(startNanos), String.valueOf(failure.getMessage()));
        }
    }

    private ServiceCheck probeCoturn() {
        long startNanos = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(turnProperties.host(), turnProperties.port()),
                    TURN_CONNECT_TIMEOUT_MILLIS);
            return new ServiceCheck(UP, latencyMillis(startNanos), null);
        } catch (Exception failure) {
            return new ServiceCheck(DOWN, latencyMillis(startNanos), String.valueOf(failure.getMessage()));
        }
    }

    /** Runs one probe on the shared pool; a probe that outlives the budget reports TIMEOUT. */
    private ServiceCheck bounded(Supplier<ServiceCheck> probe) {
        Future<ServiceCheck> future = probeExecutor.submit(probe::get);
        try {
            return future.get(PROBE_BUDGET.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException timedOut) {
            return new ServiceCheck(TIMEOUT, null, "no answer within " + PROBE_BUDGET.toSeconds() + " s");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ServiceCheck(TIMEOUT, null, "interrupted while probing");
        } catch (ExecutionException probeThrew) {
            // Probe methods catch everything themselves; this is a belt-and-braces guard.
            return new ServiceCheck(DOWN, null, String.valueOf(probeThrew.getCause()));
        }
    }

    // --- docker facet (one snapshot for all rows) ---

    private record PsLine(List<String> names, String state, String statusText) {}

    /**
     * A row's container facet, or NULL when the facet is off entirely: a disabled
     * docker facet (the prod profile — oak itself runs in a container, the host's
     * docker socket is deliberately not mounted) sends no container side at all; a
     * null on the wire reads as "this deployment cannot answer that" instead of an
     * UNKNOWN row whose "docker checks disabled" detail looked like a warning the
     * user could act on (live report 2026-10-13).
     */
    private ContainerCheck containerOf(Map<String, ContainerCheck> containers, String name) {
        if (!dockerEnabled) {
            return null;
        }
        return containers.getOrDefault(name, notConfigured());
    }

    /** Configured container name → its state; docker problems degrade to UNKNOWN rows. */
    private Map<String, ContainerCheck> containerSnapshot() {
        if (!dockerEnabled) {
            return Map.of(); // the facet is off — every row's containerOf is null
        }
        Map<String, ContainerCheck> out = new LinkedHashMap<>();
        List<PsLine> lines;
        try {
            lines = parseDockerPs(runner.run(dockerTimeout, "docker", "ps", "-a",
                    "--format", "{{.Names}}\t{{.State}}\t{{.Status}}"));
        } catch (IOException | RuntimeException failure) {
            for (String name : containerNames) {
                out.put(name, new ContainerCheck(UNKNOWN, null, "docker unavailable: " + failure.getMessage()));
            }
            return out;
        }
        for (String name : containerNames) {
            out.put(name, containerCheckFor(lines, name));
        }
        return out;
    }

    private static ContainerCheck containerCheckFor(List<PsLine> lines, String name) {
        String listing = String.join("\n", lines.stream().flatMap(line -> line.names().stream()).toList());
        String candidate = ContainerLogCollector.resolveContainer(listing, name);
        if (candidate == null) {
            return new ContainerCheck(ABSENT, null, "no container matches \"" + name + "\"");
        }
        return lines.stream()
                .filter(line -> line.names().contains(candidate))
                .findFirst()
                .map(line -> new ContainerCheck(line.state().toUpperCase(Locale.ROOT), healthOf(line.statusText()),
                        line.statusText()))
                .orElseGet(() -> new ContainerCheck(ABSENT, null, "no container matches \"" + name + "\""));
    }

    /** Parses the tab-separated {@code docker ps -a --format} output; malformed lines are skipped. */
    static List<PsLine> parseDockerPs(String output) {
        List<PsLine> lines = new ArrayList<>();
        for (String rawLine : output.split("\n")) {
            String[] parts = rawLine.trim().split("\t");
            if (parts.length != 3 || parts[0].isBlank()) {
                continue;
            }
            List<String> names = Arrays.stream(parts[0].split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            lines.add(new PsLine(names, parts[1], parts[2]));
        }
        return lines;
    }

    /** The image HEALTHCHECK verdict rides the Status column as a parenthesised suffix. */
    static String healthOf(String statusText) {
        String lowered = statusText.toLowerCase(Locale.ROOT);
        if (lowered.contains("unhealthy")) {
            return UNHEALTHY;
        }
        if (lowered.contains("healthy")) {
            return HEALTHY;
        }
        if (lowered.contains("starting")) {
            return STARTING;
        }
        return NONE;
    }

    private static ContainerCheck notConfigured() {
        return new ContainerCheck(UNKNOWN, null, "not listed in oak.health.docker.containers");
    }

    private static Integer latencyMillis(long startNanos) {
        return (int) Math.min((System.nanoTime() - startNanos) / 1_000_000, Integer.MAX_VALUE);
    }
}
