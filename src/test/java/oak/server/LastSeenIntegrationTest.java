package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.dto.UserCameOnlineEvent;
import oak.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code users.last_seen_at} (V22): a {@link UserCameOnlineEvent} — what the
 * presence tracker fires on a real visit — stamps the column via the
 * {@code LastSeenRecorder}; the event path is exercised directly (the MockMvc
 * world has no STOMP connects).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LastSeenIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private UserRepository users;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void aVisitStampsLastSeen() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody("visitor", "supersecret")))
                .andExpect(status().isCreated());
        // no visit yet — the column is untouched
        org.junit.jupiter.api.Assertions.assertNull(
                users.findByUsername("visitor").orElseThrow().getLastSeenAt());

        events.publishEvent(new UserCameOnlineEvent("visitor"));

        // the stamp is a bulk UPDATE — it bypasses the persistence context, so
        // the cached entity must be dropped before the assertion re-reads it
        entityManager.clear();
        assertNotNull(users.findByUsername("visitor").orElseThrow().getLastSeenAt());
    }
}
