package oak.server;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the roster/presence REST endpoints on the in-memory H2 test
 * profile. Mirrors {@code ChannelMessageFlowIntegrationTest}.
 *
 * <p>The WS-tier transition logic is exercised by {@code PresenceTrackerTest}; MockMvc
 * has no STOMP sessions, so {@code GET /api/presence} is only shown to return an empty
 * roster here (the known MockMvc/WS gap noted in {@code oak-server/CLAUDE.md}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UsersPresenceIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listUsersReturnsRosterWithoutEmails() throws Exception {
        registerAndLogin("alice", "alice@oak.local", "supersecret");
        registerAndLogin("bob", "bob@oak.local", "supersecret");
        String token = registerAndLogin("carol", "carol@oak.local", "supersecret");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[*].username", Matchers.hasItems("alice", "bob", "carol")))
                .andExpect(jsonPath("$[0].email").doesNotExist()); // UserSummary leaks no email

        // listing without a token -> 401
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void presenceSnapshotIsEmptyWithoutWsSessions() throws Exception {
        String token = registerAndLogin("alice", "alice@oak.local", "supersecret");

        mockMvc.perform(get("/api/presence").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private String registerAndLogin(String username, String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}", username, email, password)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }
}
