package oak.server;

import oak.server.service.ContainerLogCollector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The docker-CLI tail: resolution of compose-style container names, the graceful
 * "unavailable" degradation, and the disabled switch. The process spawn itself is not
 * unit-tested (the real {@code docker} binary is a live-environment concern — smoke covers it).
 */
class ContainerLogCollectorTest {

    @Test
    void collectsTheTailOfEachConfiguredContainer() {
        ContainerLogCollector.CommandRunner fake = (timeout, command) -> {
            if (command.length > 1 && "ps".equals(command[1])) {
                return "some-other-1\nenv-postgres-1\n";
            }
            if (command.length > 1 && "logs".equals(command[1])) {
                return "2026-10-06T10:00:00Z postgres ready\n2026-10-06T10:00:05Z postgres: ok";
            }
            throw new IOException("unexpected command " + String.join(" ", command));
        };
        ContainerLogCollector collector =
                new ContainerLogCollector(true, "postgres", 500, Duration.ofSeconds(5), fake);

        Map<String, String> logs = collector.collect();

        assertEquals(1, logs.size());
        assertTrue(logs.get("postgres").contains("postgres ready"));
        assertTrue(logs.get("postgres").contains("postgres: ok"));
    }

    @Test
    void dockerFailureDegradesToAnUnavailableNote() {
        ContainerLogCollector.CommandRunner failing = (timeout, command) -> {
            throw new IOException("Cannot run program \"docker\": CreateProcess error=2");
        };
        ContainerLogCollector collector =
                new ContainerLogCollector(true, "postgres,minio", 2000, Duration.ofSeconds(5), failing);

        Map<String, String> logs = collector.collect();

        assertEquals(2, logs.size());
        assertTrue(logs.get("postgres").startsWith("unavailable:"));
        assertTrue(logs.get("minio").startsWith("unavailable:"));
    }

    @Test
    void missingContainerDegradesToAnUnavailableNote() {
        ContainerLogCollector.CommandRunner emptyListing = (timeout, command) -> {
            if (command.length > 1 && "ps".equals(command[1])) {
                return "";
            }
            throw new IOException("logs must not run when the container was not found");
        };
        ContainerLogCollector collector =
                new ContainerLogCollector(true, "janus", 2000, Duration.ofSeconds(5), emptyListing);

        Map<String, String> logs = collector.collect();

        assertTrue(logs.get("janus").contains("no running container matches"));
    }

    @Test
    void disabledCollectsNothing() {
        ContainerLogCollector.CommandRunner mustNotRun = (timeout, command) -> {
            throw new IOException("collect() must not spawn anything when disabled");
        };
        ContainerLogCollector collector =
                new ContainerLogCollector(false, "postgres", 2000, Duration.ofSeconds(5), mustNotRun);

        assertEquals(Map.of(), collector.collect());
    }

    @Test
    void resolveContainerMatchesExactAndComposeStyleNames() {
        String listing = "oak-postgres-1\nsome-postgres-extra-2\nminio\nother-1";
        assertEquals("oak-postgres-1", ContainerLogCollector.resolveContainer(listing, "postgres"));
        assertEquals("minio", ContainerLogCollector.resolveContainer(listing, "minio"));
        assertNull(ContainerLogCollector.resolveContainer(listing, "absent"));
        // A name embedded in the middle must NOT match — only the exact name or a <-name-N> suffix.
        assertNull(ContainerLogCollector.resolveContainer("some-postgres-extra-2\nplain-1", "postgres"));
    }
}
