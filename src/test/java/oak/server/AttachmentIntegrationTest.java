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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the MinIO attachment flow on the in-memory H2 test profile. MinIO is
 * disabled here (noop signer), so {@code uploadUrl} is null — the real upload/download is
 * exercised only by the end-to-end smoke test. Mirrors {@code ChannelMessageFlowIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttachmentIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void attachmentEndpointRequiresAuthIssuesKeyAndMessageAcceptsAttachment() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(token, "general", "TEXT");

        // requesting an upload slot without a token -> 401
        mockMvc.perform(post("/api/attachments").contentType(JSON).content("{\"fileName\":\"pic.png\"}"))
                .andExpect(status().isUnauthorized());

        // with a token -> 200 + a non-empty object key (uploadUrl is null under the noop signer)
        mockMvc.perform(post("/api/attachments").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"fileName\":\"pic.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").isNotEmpty());

        // an attachment-only message (empty text) is valid -> 201
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"content\":\"\",\"attachmentKey\":\"some-key\",\"attachmentFileName\":\"pic.png\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentFileName").value("pic.png"))
                .andExpect(jsonPath("$.authorUsername").value("alice"));

        // a message with neither content nor attachment -> 400
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
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
