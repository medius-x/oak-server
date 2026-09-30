package oak.server;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
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
 * MockMvc coverage for the Janus room-info endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RoomControllerIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void roomRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/channels/1/room")).andExpect(status().isUnauthorized());
    }

    @Test
    void roomReturnsJanusUrlAndRoomId() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        Long channelId = createVoiceChannel(token);

        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.janusUrl").value("http://localhost:8088/janus"))
                .andExpect(jsonPath("$.roomId").value(channelId))
                // noop voice-access service (admin-enabled=false in the test profile)
                .andExpect(jsonPath("$.token").value("test-token"))
                .andExpect(jsonPath("$.pin").value("test-pin"));
    }

    @Test
    void roomNotFoundForMissingChannel() throws Exception {
        String token = registerAndLogin("bob", "supersecret");

        mockMvc.perform(get("/api/channels/999999/room").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private Long createVoiceChannel(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"name\":\"voice\",\"type\":\"VOICE\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
