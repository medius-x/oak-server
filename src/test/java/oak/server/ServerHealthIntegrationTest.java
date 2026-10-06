package oak.server;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The health endpoint (healthcheck round 2026-10-07): MANAGE_STORAGE gates it like the rest of
 * the "Server" tab. The test profile points the probes at what it actually has — H2 (postgres
 * UP), no MinIO, no Janus admin — and disables the docker facet: the developer machine HAS a
 * live Docker, and the test must not read it. The coturn TCP probe is machine-dependent (the
 * dev host may or may not listen on the port) — only its presence is asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "oak.health.docker.enabled=false")
@Transactional
class ServerHealthIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void endpointIsAuthenticated() throws Exception {
        mockMvc.perform(get("/api/server/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void endpointIsGatedByManageStorage() throws Exception {
        registerAndLogin("health-owner"); // first account = OWNER — holds MANAGE_STORAGE implicitly
        String memberToken = registerAndLogin("health-plain-member");
        mockMvc.perform(get("/api/server/health").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerSeesTheFourServices() throws Exception {
        String ownerToken = registerAndLogin("health-archivist");
        mockMvc.perform(get("/api/server/health").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.services.length()").value(4))
                // the probes answer for what the test profile really has
                .andExpect(jsonPath("$.services[?(@.name == 'postgres')].service.status", hasItem("UP")))
                .andExpect(jsonPath("$.services[?(@.name == 'minio')].service.status", hasItem("DISABLED")))
                .andExpect(jsonPath("$.services[?(@.name == 'janus')].service.status", hasItem("DISABLED")))
                // the docker facet is switched off above — every row degrades to UNKNOWN
                .andExpect(jsonPath("$.services[?(@.name == 'postgres')].container.status", hasItem("UNKNOWN")))
                // machine-dependent (the dev host may listen on the TURN port) — present, not valued
                .andExpect(jsonPath("$.services[?(@.name == 'coturn')].service.status").exists());
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, "supersecret123")))
                .andExpect(status().isCreated());
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret123\"}", username)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
