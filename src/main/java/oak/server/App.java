package oak.server;

import lombok.extern.slf4j.Slf4j;
import oak.server.entity.User;
import oak.server.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * OAK server entry point.
 *
 * Boots the Spring Boot application and, on startup, probes the PostgreSQL
 * connection through JPA by counting rows in the {@code users} table. A
 * successful boot therefore proves the datasource + Hibernate wiring
 * end-to-end (schema creation via ddl-auto=update + a real read query).
 *
 * <p>{@link EnableScheduling} enables the periodic refresh-token cleanup
 * (see {@link oak.server.service.RefreshTokenCleanup}).
 */
@Slf4j
@SpringBootApplication
@EnableScheduling
public class App {

    /** The IPv6 liveness probe's target (2026-11-23, the SGDB icon
     *  post-mortem): the CDN host our icon downloads die on at broken-v6
     *  hostings. Any dual-stack external host would do; this is the one we
     *  actually depend on. */
    static final String IP_PROBE_HOST = "cdn2.steamgriddb.com";

    public static void main(String[] args) {
        preferWorkingIpStack();
        SpringApplication.run(App.class, args);
    }

    /** Broken-IPv6 hosting cure (2026-11-23): Java's resolver orders AAAA
     *  first, and a host with a firewalled v6 stack turns every outbound call
     *  into a silent read-timeout (the live log: CDN downloads dying on a box
     *  where {@code curl -4} fetches the same URL and {@code curl -6} hangs).
     *  Must run BEFORE the first socket opens anything else — main()'s first
     *  statement, ahead of Tomcat and every outbound client. The probe: when
     *  the CDN has an AAAA record, try a 2 s TCP connect over it; only a DEAD
     *  v6 pins the JVM to IPv4 (inbound listeners included — fine behind the
     *  Docker/Caddy v4 proxying every deployment we ship). A live v6 leaves
     *  the JVM's own choice untouched. Honest limit: the probe sees TCP, not
     *  a strangled-TLS middlebox — that exotic case still needs the pin.
     */
    private static void preferWorkingIpStack() {
        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(IP_PROBE_HOST);
            java.net.InetAddress v6 = null;
            for (java.net.InetAddress address : addresses) {
                if (address instanceof java.net.Inet6Address) {
                    v6 = address;
                    break;
                }
            }
            if (v6 == null) {
                return; // the host is v4-only — nothing to prefer
            }
            if (ipv6Reachable(v6, 2_000)) {
                log.info("IPv6 probe to {} passed — leaving the JVM's own stack choice alone", IP_PROBE_HOST);
                return;
            }
            System.setProperty("java.net.preferIPv4Stack", "true");
            log.warn("IPv6 probe to {} failed — pinning the JVM to IPv4 for every outbound call", IP_PROBE_HOST);
        } catch (java.net.UnknownHostException dns) {
            // DNS itself is down at boot — not a stack problem; the outbound
            // breakers own whatever follows.
            log.warn("IPv6 probe skipped ({} did not resolve: {})", IP_PROBE_HOST, dns.getMessage());
        }
    }

    /** One TCP connect over the given v6 address — the liveness test. */
    static boolean ipv6Reachable(java.net.InetAddress v6, int timeoutMillis) {
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new java.net.InetSocketAddress(v6, 443), timeoutMillis);
            return true;
        } catch (java.io.IOException unreachable) {
            return false;
        }
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
