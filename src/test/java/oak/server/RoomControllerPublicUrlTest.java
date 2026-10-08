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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The TLS-entry-point split of the two Janus routes (provisioning round B): with
 * {@code oak.janus.public-rest-url} set, {@code GET /api/channels/{id}/room} must hand clients
 * the PUBLIC https URL, while the bound {@link oak.server.config.JanusProperties#restUrl()}
 * (what the server itself dials for room lifecycle) stays the internal address.
 */
@SpringBootTest(properties = "oak.janus.public-rest-url=https://203.0.113.10/janus")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RoomControllerPublicUrlTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Autowired
    private oak.server.config.JanusProperties janusProperties;

    @Test
    void roomHandsClientsThePublicUrlWhileTheServerKeepsTheInternalOne() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        Long channelId = createVoiceChannel(token);

        mockMvc.perform(get("/api/channels/" + channelId + "/room").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.janusUrl").value("https://203.0.113.10/janus"));

        assertEquals("http://localhost:8088/janus", janusProperties.restUrl());
        assertEquals("https://203.0.113.10/janus", janusProperties.clientRestUrl());
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
