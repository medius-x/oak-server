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
 * MockMvc coverage for {@code @}-mention parsing through the real message pipeline: {@code POST}
 * writes a message, {@code GET} history returns it with the {@code mentions} field populated by
 * {@code MessageService.toResponse} (the single parse chokepoint). Covers USER/EVERYONE/HERE
 * resolution and {@code MENTION_EVERYONE} gating (the OWNER has it; a roleless member does not).
 * Role-mention resolution is unit-tested in {@code MentionParserTest} (it needs role mocking).
 *
 * <p>Index math for offsets: {@code "hi @bob and @everyone"} → {@code @bob} at [3,7),
 * {@code @everyone} at [12,21).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MentionParsingIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ownerMentionsProduceUserAndEveryoneWithOffsets() throws Exception {
        String alice = registerAndLogin("alice");   // first = OWNER → has MENTION_EVERYONE
        registerAndLogin("bob");
        long channelId = createChannel(alice, "general");

        send(alice, channelId, "hi @bob and @everyone");

        mockMvc.perform(get("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("hi @bob and @everyone"))
                .andExpect(jsonPath("$[0].mentions.length()").value(2))
                .andExpect(jsonPath("$[0].mentions[0].type").value("USER"))
                .andExpect(jsonPath("$[0].mentions[0].name").value("bob"))
                .andExpect(jsonPath("$[0].mentions[0].start").value(3))
                .andExpect(jsonPath("$[0].mentions[0].end").value(7))
                .andExpect(jsonPath("$[0].mentions[1].type").value("EVERYONE"))
                .andExpect(jsonPath("$[0].mentions[1].start").value(12))
                .andExpect(jsonPath("$[0].mentions[1].end").value(21));
    }

    @Test
    void rolelessMemberCannotMentionEveryone() throws Exception {
        String alice = registerAndLogin("alice");   // creates the channel (OWNER has MANAGE_CHANNELS)
        String bob = registerAndLogin("bob");        // roleless — no MENTION_EVERYONE
        long channelId = createChannel(alice, "other");

        send(bob, channelId, "@everyone test");

        // bob's @everyone stays plain text: no mention recorded.
        mockMvc.perform(get("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("@everyone test"))
                .andExpect(jsonPath("$[0].mentions.length()").value(0));
    }

    @Test
    void ownerCanMentionHere() throws Exception {
        String alice = registerAndLogin("alice");
        long channelId = createChannel(alice, "voice-meta");

        send(alice, channelId, "@here folks");

        mockMvc.perform(get("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mentions[0].type").value("HERE"))
                .andExpect(jsonPath("$[0].mentions.length()").value(1));
    }

    private long createChannel(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(channelBody(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void send(String token, long channelId, String content) throws Exception {
        mockMvc.perform(post("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(contentBody(content)))
                .andExpect(status().isCreated());
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret\"}", username)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret\"}", username)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private static String channelBody(String name) {
        return String.format("{\"name\":\"%s\",\"type\":\"TEXT\"}", name);
    }

    private static String contentBody(String content) {
        // JSON-escape the message text so quotes/backslashes inside content survive.
        return "{\"content\":" + JsonOutput.quote(content) + "}";
    }

    /** Minimal JSON string quoter (avoids pulling a JSON lib into the test). */
    private static final class JsonOutput {
        static String quote(String value) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    default -> sb.append(ch);
                }
            }
            return sb.append('"').toString();
        }
    }
}
