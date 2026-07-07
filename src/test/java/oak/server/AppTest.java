package oak.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline smoke test — does NOT boot the Spring context and does NOT touch
 * the database, so {@code mvn package} stays green without PostgreSQL running.
 * End-to-end JPA verification happens via the CommandLineRunner in {@link App}
 * when the app is actually run against a live database.
 */
class AppTest {

    @Test
    void junitPlatformIsWiredUp() {
        assertTrue(true, "JUnit 5 (Jupiter) is available via spring-boot-starter-test");
    }
}
