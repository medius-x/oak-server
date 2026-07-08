package oak.server;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth flow through MockMvc against the full Spring context backed by
 * an in-memory H2 database (test profile). Acts as a safety net over the auth
 * behavior: HTTP statuses, bean validation, JWT issuance/validation, BCrypt.
 *
 * <p>{@code @Transactional} rolls back each test method, so methods are isolated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthFlowIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void register_returnsCreatedUserWithoutPasswordHash() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("alice", "alice@oak.local", "supersecret")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@oak.local"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        register("alice", "alice@oak.local", "supersecret");

        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("alice", "alice2@oak.local", "supersecret")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void register_invalidBody_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody("ab", "not-an-email", "123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void login_validCredentials_returnsBearerToken() throws Exception {
        register("alice", "alice@oak.local", "supersecret");

        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody("alice", "supersecret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andReturn();

        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        assertEquals(3, token.split("\\.").length, "a JWT has three dot-separated parts");
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        register("alice", "alice@oak.local", "supersecret");

        mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody("alice", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withValidToken_returnsProfile() throws Exception {
        register("alice", "alice@oak.local", "supersecret");
        String token = login("alice", "supersecret");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@oak.local"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void me_withTamperedToken_returns401() throws Exception {
        register("alice", "alice@oak.local", "supersecret");
        String token = login("alice", "supersecret");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token + "XYZ"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private void register(String username, String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody(username, email, password)))
                .andExpect(status().isCreated());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private static String regBody(String username, String email, String password) {
        return String.format("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}", username, email, password);
    }

    private static String loginBody(String username, String password) {
        return String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
    }
}
