package oak.server;

import io.minio.MinioClient;
import oak.server.config.JanusProperties;
import oak.server.config.MinioProperties;
import oak.server.config.TurnProperties;
import oak.server.dto.ServerHealthResponse;
import oak.server.service.ContainerLogCollector;
import oak.server.service.JanusAdminClient;
import oak.server.service.JanusAdminException;
import oak.server.service.ServerHealthService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The health collector (healthcheck round 2026-10-07): the four probes against real in-process
 * stand-ins (an H2 datasource, a listening/closed loopback socket, a mocked Janus admin client),
 * and the docker facet against a fake {@code docker ps -a} — the process spawn itself stays a
 * live-environment concern (smoke covers it). Disabled integrations must read DISABLED, and every
 * docker problem must degrade its row to UNKNOWN, never fail the snapshot.
 */
class ServerHealthServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final MinioProperties MINIO_DISABLED =
            new MinioProperties("http://localhost:9000", "http://localhost:9000", "ak", "sk", "bucket", false);
    private static final JanusProperties JANUS_DISABLED = new JanusProperties(
            "http://localhost:8088/janus", null, "ws://localhost:8188", "secret", "http://localhost:7088/admin", false);
    private static final JanusProperties JANUS_ENABLED = new JanusProperties(
            "http://localhost:8088/janus", null, "ws://localhost:8188", "secret", "http://localhost:7088/admin", true);

    @Test
    void postgresProbeIsUpOnARealDataSource() {
        ServerHealthResponse health = service(h2DataSource(), providerOf(null), MINIO_DISABLED,
                providerOf(null), JANUS_DISABLED, turnProps(1), false, mustNotRunDocker()).collect();

        ServerHealthResponse.Entry postgres = entryOf(health, "postgres");
        assertEquals("UP", postgres.service().status());
        assertNotNull(postgres.service().latencyMillis());
        assertEquals(4, health.services().size());
    }

    @Test
    void disabledIntegrationsReportDisabledAndDockerStaysUnknown() {
        ServerHealthResponse health = service(h2DataSource(), providerOf(null), MINIO_DISABLED,
                providerOf(null), JANUS_DISABLED, turnProps(1), false, mustNotRunDocker()).collect();

        assertEquals("DISABLED", entryOf(health, "minio").service().status());
        assertEquals("disabled by configuration", entryOf(health, "minio").service().detail());
        assertEquals("DISABLED", entryOf(health, "janus").service().status());
        // docker checks are off in this collect() — the runner above would have thrown otherwise
        assertEquals("UNKNOWN", entryOf(health, "postgres").container().status());
    }

    @Test
    void janusProbeReadsTheGatewayVerdict() {
        JanusAdminClient gateway = mock(JanusAdminClient.class);
        ServerHealthService healthService = service(h2DataSource(), providerOf(null), MINIO_DISABLED,
                providerOf(gateway), JANUS_ENABLED, turnProps(1), false, mustNotRunDocker());

        when(gateway.listTokens()).thenReturn(MAPPER.readTree("{\"janus\":\"success\"}"));
        assertEquals("UP", entryOf(healthService.collect(), "janus").service().status());

        // The gateway answered but refused (e.g. a secret mismatch): alive transport, broken
        // admin relation — for voice that is an outage, so DOWN with the gateway's reason.
        when(gateway.listTokens()).thenReturn(
                MAPPER.readTree("{\"janus\":\"error\",\"error\":{\"reason\":\"Unauthorized request\"}}"));
        ServerHealthResponse.Entry refused = entryOf(healthService.collect(), "janus");
        assertEquals("DOWN", refused.service().status());
        assertEquals("Unauthorized request", refused.service().detail());

        when(gateway.listTokens()).thenThrow(
                new JanusAdminException("Janus request failed (http://localhost:7088/admin): Connection refused"));
        ServerHealthResponse.Entry unreachable = entryOf(healthService.collect(), "janus");
        assertEquals("DOWN", unreachable.service().status());
        assertTrue(unreachable.service().detail().contains("Connection refused"));
    }

    @Test
    void coturnProbeConnectsToTheConfiguredPort() throws IOException {
        try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ServerHealthResponse up = service(h2DataSource(), providerOf(null), MINIO_DISABLED,
                    providerOf(null), JANUS_DISABLED, turnProps(listening.getLocalPort()),
                    false, mustNotRunDocker()).collect();
            assertEquals("UP", entryOf(up, "coturn").service().status());
        }
        int closedPort;
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = taken.getLocalPort();
        }
        ServerHealthResponse down = service(h2DataSource(), providerOf(null), MINIO_DISABLED,
                providerOf(null), JANUS_DISABLED, turnProps(closedPort), false, mustNotRunDocker()).collect();
        assertEquals("DOWN", entryOf(down, "coturn").service().status());
    }

    @Test
    void dockerFacetCarriesStateAndHealthOfPinnedNames() {
        String psOutput = "oak-postgres\trunning\tUp 2 hours (healthy)\n"
                + "oak-janus\texited\tExited (1) 2 minutes ago\n"
                + "oak-minio\trunning\tUp 4 minutes (unhealthy)\n";
        ServerHealthResponse health = service(h2DataSource(), providerOf(null), MINIO_DISABLED,
                providerOf(null), JANUS_DISABLED, turnProps(1), true,
                (timeout, command) -> psOutput).collect();

        ServerHealthResponse.ContainerCheck postgres = entryOf(health, "postgres").container();
        assertEquals("RUNNING", postgres.status());
        assertEquals("HEALTHY", postgres.health());
        assertEquals("Up 2 hours (healthy)", postgres.detail());

        ServerHealthResponse.ContainerCheck janus = entryOf(health, "janus").container();
        assertEquals("EXITED", janus.status());
        assertEquals("NONE", janus.health());

        // "unhealthy" contains "healthy" — the suffix parser must check the negative first
        ServerHealthResponse.ContainerCheck minio = entryOf(health, "minio").container();
        assertEquals("RUNNING", minio.status());
        assertEquals("UNHEALTHY", minio.health());

        // configured but not present in docker ps -a
        ServerHealthResponse.ContainerCheck coturn = entryOf(health, "coturn").container();
        assertEquals("ABSENT", coturn.status());
        assertTrue(coturn.detail().contains("no container matches"));
    }

    @Test
    void dockerSnapshotIgnoresMalformedLinesAndCommaJoinedNames() {
        String psOutput = "junk-without-tabs\n"
                + "oak-postgres,alias-postgres\trunning\tUp 5 seconds (starting)\n"
                + "\n";
        ServerHealthResponse health = service(h2DataSource(), providerOf(null), MINIO_DISABLED,
                providerOf(null), JANUS_DISABLED, turnProps(1), true,
                (timeout, command) -> psOutput).collect();

        ServerHealthResponse.ContainerCheck postgres = entryOf(health, "postgres").container();
        assertEquals("RUNNING", postgres.status());
        assertEquals("STARTING", postgres.health());
    }

    @Test
    void dockerFailureDegradesEveryContainerToUnknown() {
        ContainerLogCollector.CommandRunner failing = (timeout, command) -> {
            throw new IOException("Cannot run program \"docker\": CreateProcess error=2");
        };
        ServerHealthResponse health = service(h2DataSource(), providerOf(null), MINIO_DISABLED,
                providerOf(null), JANUS_DISABLED, turnProps(1), true, failing).collect();

        for (ServerHealthResponse.Entry entry : health.services()) {
            assertEquals("UNKNOWN", entry.container().status());
            assertTrue(entry.container().detail().contains("docker unavailable"));
        }
    }

    // --- fixtures ---

    private static ServerHealthResponse.Entry entryOf(ServerHealthResponse health, String name) {
        return health.services().stream()
                .filter(entry -> entry.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + name));
    }

    private static ServerHealthService service(DataSource dataSource,
                                               ObjectProvider<MinioClient> minioProvider,
                                               MinioProperties minioProperties,
                                               ObjectProvider<JanusAdminClient> janusProvider,
                                               JanusProperties janusProperties,
                                               TurnProperties turnProperties,
                                               boolean dockerEnabled,
                                               ContainerLogCollector.CommandRunner runner) {
        return new ServerHealthService(dataSource, minioProvider, minioProperties, janusProvider,
                janusProperties, turnProperties, dockerEnabled, "postgres,minio,janus,coturn",
                Duration.ofSeconds(5), runner);
    }

    private static TurnProperties turnProps(int port) {
        return new TurnProperties("127.0.0.1", port, "secret", Duration.ofHours(1), "oak.local");
    }

    private static DataSource h2DataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:server-health-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private static ContainerLogCollector.CommandRunner mustNotRunDocker() {
        return (timeout, command) -> {
            throw new IOException("docker must not run in this test: " + String.join(" ", command));
        };
    }

    /** ObjectProvider stand-in for the plain-JUnit seam (no Spring context here). */
    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                if (value == null) {
                    throw new NoSuchElementException("no bean");
                }
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? Collections.<T>emptyIterator()
                        : Collections.singletonList(value).iterator();
            }
        };
    }
}
