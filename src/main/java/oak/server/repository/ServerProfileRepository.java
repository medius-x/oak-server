package oak.server.repository;

import oak.server.entity.ServerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Access to the single {@link ServerProfile} row (the instance identity). The row is addressed as
 * "the earliest one" (not a hard-coded id) so it stays robust to the IDENTITY sequence starting
 * above 1; {@link #singleton()} loads it or fails fast — the {@code ServerProfileSeeder} guarantees
 * the row exists before any request.
 */
public interface ServerProfileRepository extends JpaRepository<ServerProfile, Long> {

    /** The single profile row, seeded at startup. Throws if missing (seeder did not run). */
    default ServerProfile singleton() {
        return findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException(
                        "ServerProfile row missing — ServerProfileSeeder did not run"));
    }

    /** The earliest profile row, if any (idempotency check for the seeder). */
    java.util.Optional<ServerProfile> findFirstByOrderByIdAsc();
}
