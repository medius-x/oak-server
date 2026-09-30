package oak.server;

import com.jayway.jsonpath.JsonPath;
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
 * End-to-end channels/messages flow through MockMvc on the in-memory H2 test profile.
 * Authenticates as a real registered user, then exercises channel + message endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ChannelMessageFlowIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void createChannel_postMessage_listMessages_thenGuards() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(token, "general", "TEXT");

        // channel appears in the list
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("general"))
                .andExpect(jsonPath("$[0].type").value("TEXT"));

        // post two messages as alice (two rows exercise the history-ordering path)
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"content\":\"hello world\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("hello world"))
                .andExpect(jsonPath("$.authorUsername").value("alice"))
                .andExpect(jsonPath("$.channelId").value(channelId));
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"content\":\"second one\"}"))
                .andExpect(status().isCreated());

        // history returns the newest N messages in chronological order (oldest-first)
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("hello world"))
                .andExpect(jsonPath("$[1].content").value("second one"));

        // posting without a token -> 401
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .contentType(JSON)
                        .content("{\"content\":\"ghost\"}"))
                .andExpect(status().isUnauthorized());

        // posting to an unknown channel -> 404
        mockMvc.perform(post("/api/channels/999999/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"content\":\"where am i\"}"))
                .andExpect(status().isNotFound());

        // empty content -> 400 (bean validation)
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // --- helpers ---

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

    private Long createChannel(String token, String name, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"type\":\"%s\"}", name, type)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
