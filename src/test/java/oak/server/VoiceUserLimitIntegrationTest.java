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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The V18 channel user-limit plumbing: create-time and PATCH validation, the wire field, and the
 * MANAGE_CHANNELS gate. The entry gate itself lives in {@code RoomControllerIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class VoiceUserLimitIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void voiceChannelMayBeBornWithALimit() throws Exception {
        String token = registerAndLogin("alice", "supersecret");

        mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"name\":\"limited\",\"type\":\"VOICE\",\"userLimit\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userLimit").value(5));
    }

    @Test
    void unlimitedChannelsAnswerNull() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(token, "{\"name\":\"free\",\"type\":\"VOICE\"}");

        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(String.format("$[?(@.id == %d)].userLimit", channelId),
                        Matchers.hasItem(Matchers.nullValue())));
    }

    @Test
    void onlyVoiceChannelsCarryALimit() throws Exception {
        String token = registerAndLogin("alice", "supersecret");

        mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"name\":\"text\",\"type\":\"TEXT\",\"userLimit\":5}"))
                .andExpect(status().isBadRequest());

        Long textId = createChannel(token, "{\"name\":\"text\",\"type\":\"TEXT\"}");
        mockMvc.perform(patch("/api/channels/" + textId + "/user-limit").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"userLimit\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limitRangeIsValidated() throws Exception {
        String token = registerAndLogin("alice", "supersecret");

        mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"name\":\"zero\",\"type\":\"VOICE\",\"userLimit\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"name\":\"big\",\"type\":\"VOICE\",\"userLimit\":100}"))
                .andExpect(status().isBadRequest());

        Long channelId = createChannel(token, "{\"name\":\"limited\",\"type\":\"VOICE\",\"userLimit\":2}");
        mockMvc.perform(patch("/api/channels/" + channelId + "/user-limit").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"userLimit\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchSetsAndClearsTheLimit() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(token, "{\"name\":\"limited\",\"type\":\"VOICE\",\"userLimit\":2}");

        mockMvc.perform(patch("/api/channels/" + channelId + "/user-limit").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"userLimit\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userLimit").value(3));

        // null (or an absent field) CLEARS the limit — the unlimited state is expressible.
        mockMvc.perform(patch("/api/channels/" + channelId + "/user-limit").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userLimit").value(Matchers.nullValue()));

        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(String.format("$[?(@.id == %d)].userLimit", channelId),
                        Matchers.hasItem(Matchers.nullValue())));
    }

    @Test
    void settingTheLimitRequiresManageChannels() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "{\"name\":\"limited\",\"type\":\"VOICE\",\"userLimit\":2}");
        String bobToken = loginToken("bob", "supersecret");

        mockMvc.perform(patch("/api/channels/" + channelId + "/user-limit").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"userLimit\":3}"))
                .andExpect(status().isForbidden());
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
        return loginToken(username, password);
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private Long createChannel(String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
