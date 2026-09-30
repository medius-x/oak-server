package oak.server;

import oak.server.entity.Invite;
import oak.server.entity.User;
import oak.server.repository.InviteRepository;
import oak.server.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Test-support bean for the invite-gated registration: builds register bodies that carry a
 * freshly minted one-hour secret whenever the instance already has users (the first user of
 * each test stays the OWNER bootstrap — no secret, exactly as production). Lives in the
 * scanned {@code oak.server} package of the test tree, so every {@code @SpringBootTest}
 * context picks it up; the shared-context caching is unaffected (a plain singleton).
 *
 * <p>Runs inside the test's transaction (MockMvc + {@code @Transactional} share it), so the
 * minted row is visible to the register endpoint and everything rolls back together.
 */
@Component
public class TestInvites {

    @Autowired
    private InviteRepository inviteRepository;

    @Autowired
    private UserRepository userRepository;

    /** A register body for the given credentials: bare on an empty instance (the OWNER
     *  bootstrap), invite-carrying otherwise. */
    public String registrationBody(String username, String password) {
        if (userRepository.count() == 0) {
            return String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
        }
        return String.format("{\"username\":\"%s\",\"password\":\"%s\",\"inviteSecret\":\"%s\"}",
                username, password, mint());
    }

    /** Mints one active one-hour invite attributed to the earliest user; the raw secret. */
    public String mint() {
        User creator = userRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("TestInvites.mint() needs at least one user"));
        String secret = "test-" + UUID.randomUUID().toString().replace("-", "");
        inviteRepository.save(new Invite(secret, creator, Instant.now().plus(Duration.ofHours(1))));
        return secret;
    }
}
