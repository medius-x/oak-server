package oak.server;

import oak.server.entity.User;
import oak.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * OAK server entry point.
 *
 * Boots the Spring Boot application and, on startup, probes the PostgreSQL
 * connection through JPA by counting rows in the {@code users} table. A
 * successful boot therefore proves the datasource + Hibernate wiring
 * end-to-end (schema creation via ddl-auto=update + a real read query).
 */
@SpringBootApplication
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Bean
    CommandLineRunner verifyDatabase(UserRepository userRepository) {
        return args -> {
            long total = userRepository.count();
            List<String> usernames = userRepository.findAll().stream()
                    .map(User::getUsername)
                    .toList();
            log.info("[JPA-OK] Connected to PostgreSQL via JPA — 'users' table accessible, {} row(s): {}",
                    total, usernames);
        };
    }
}
