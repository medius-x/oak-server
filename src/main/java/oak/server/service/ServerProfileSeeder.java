package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.entity.ServerProfile;
import oak.server.repository.ServerProfileRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotently seeds the single {@link ServerProfile} row (the instance identity: name + icon) on
 * startup, so {@code GET /api/server} always resolves. Runs after {@code RoleSeeder} (Order 1 vs 0);
 * order is not load-bearing here, just tidy. The test profile (create-drop) is unaffected — the row
 * is re-created from scratch each run.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ServerProfileSeeder implements ApplicationRunner {

    /** Default instance name until an owner renames it via {@code PATCH /api/server}. */
    public static final String DEFAULT_NAME = "OAK Server";

    /** Manual/test connection info for fresh rows — the future provisioning flow will overwrite these
     *  with the real advertised address; rows created before the fields existed stay null until set. */
    public static final String DEFAULT_CONNECTION_HOST = "localhost";
    public static final int DEFAULT_CONNECTION_PORT = 8080;

    private final ServerProfileRepository repository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.findFirstByOrderByIdAsc().isPresent()) {
            return;
        }
        ServerProfile profile = new ServerProfile(DEFAULT_NAME);
        profile.setConnectionHost(DEFAULT_CONNECTION_HOST);
        profile.setConnectionPort(DEFAULT_CONNECTION_PORT);
        repository.save(profile);
        log.info("[Server] Seeded default server profile (name='{}')", DEFAULT_NAME);
    }
}
