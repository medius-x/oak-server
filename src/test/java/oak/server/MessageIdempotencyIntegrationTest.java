package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.service.MessageIdempotencyCache;
import org.junit.jupiter.api.AfterEach;
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
 * The message idempotency key (network resilience): a client retry whose first attempt lost its
 * response re-presents the SAME key and must receive the ORIGINAL message back — one row in the
 * history, not two. Legacy clients (null key) keep the old duplicate behaviour by design.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MessageIdempotencyIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    /** The DB rolls back between tests, but this in-memory map does NOT — clear it or the next
     *  test's "fresh" key replays a message whose row is already gone. */
    @Autowired
    private MessageIdempotencyCache idempotencyCache;

    @AfterEach
    void clearIdempotencyCache() {
        idempotencyCache.clear();
    }

    @Test
    void sameKeyReplayReturnsTheOriginalMessageWithoutDuplicating() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");

        MvcResult first = postMessageWithKey(aliceToken, channelId, "hello", "key-1");
        Long firstId = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult replay = postMessageWithKey(aliceToken, channelId, "hello", "key-1");
        Long replayId = ((Number) JsonPath.read(replay.getResponse().getContentAsString(), "$.id")).longValue();

        // The SAME message came back — and the history holds exactly one row.
        org.junit.jupiter.api.Assertions.assertEquals(firstId, replayId);
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(firstId));
    }

    @Test
    void differentKeysAndNullKeysCreateSeparateMessages() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");

        postMessageWithKey(aliceToken, channelId, "one", "key-1");
        postMessageWithKey(aliceToken, channelId, "two", "key-2");
        // Legacy shape: no key at all — repeats create rows (the pre-key behaviour).
        postMessage(aliceToken, channelId, "three");
        postMessage(aliceToken, channelId, "three");

        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void theSameKeyBelongsToDifferentAuthorsIndependently() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");

        Long aliceMessage = idOf(postMessageWithKey(aliceToken, channelId, "from alice", "shared-key"));
        Long bobMessage = idOf(postMessageWithKey(bobToken, channelId, "from bob", "shared-key"));

        // Two users, one key each — no cross-user shadowing.
        org.junit.jupiter.api.Assertions.assertNotEquals(aliceMessage, bobMessage);
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ------------------------------------------------------------------

    private Long idOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isCreated());
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
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

    private MvcResult postMessageWithKey(String token, Long channelId, String content, String key) throws Exception {
        return mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"content\":\"%s\",\"idempotencyKey\":\"%s\"}", content, key)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private void postMessage(String token, Long channelId, String content) throws Exception {
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"content\":\"%s\"}", content)))
                .andExpect(status().isCreated());
    }
}
