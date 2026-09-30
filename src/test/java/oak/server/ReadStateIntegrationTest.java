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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the read-state endpoints on the in-memory H2 test profile. The snapshot
 * counts unread messages and (parsed) mentions relative to the user's {@code lastReadMessageId};
 * ack advances the pointer; a channel the user may not read is absent from the snapshot. Registration
 * seeds the existing archive as read, so a member registered before the test messages are posted
 * sees them as unread, and one registered after does not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReadStateIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Test
    void snapshotCountsUnreadAndAckResetsIt() throws Exception {
        String alice = register("alice");              // OWNER
        long general = createChannel(alice, "general");
        String bob = register("bob");                   // seeded: general empty → lastRead null

        send(alice, general, "one");
        send(alice, general, "two");
        send(alice, general, "three");

        assertEquals(3, snapshotField(bob, general, "unreadCount"));

        // ack (mark all read) → unread drops to 0.
        ack(bob, general, null);
        assertEquals(0, snapshotField(bob, general, "unreadCount"));
    }

    @Test
    void snapshotCountsMentionsOfTheUser() throws Exception {
        String alice = register("alice");
        long general = createChannel(alice, "general");
        String bob = register("bob");

        send(alice, general, "@bob hello");             // @user mention always allowed

        assertEquals(1, snapshotField(bob, general, "unreadCount"));
        assertEquals(1, snapshotField(bob, general, "mentionCount"));
    }

    @Test
    void channelWithoutReadAccessIsAbsentFromSnapshot() throws Exception {
        String alice = register("alice");
        long general = createChannel(alice, "general");
        long secret = createChannel(alice, "secret");
        long everyoneId = everyoneRoleId(alice);

        // deny READ_MESSAGE_HISTORY to @everyone in #secret → effectively a private channel.
        mockMvc.perform(put("/api/channels/" + secret + "/overrides/" + everyoneId)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(JSON).content("{\"allow\":[],\"deny\":[\"READ_MESSAGE_HISTORY\"]}"))
                .andExpect(status().isOk());

        String bob = register("bob");

        assertTrue(channelInSnapshot(bob, general));   // @everyone can read #general
        assertFalse(channelInSnapshot(bob, secret));   // #secret is invisible to bob
    }

    private long snapshotField(String token, long channelId, String field) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/read-states").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<Number> values = JsonPath.read(result.getResponse().getContentAsString(),
                "$[?(@.channelId==" + channelId + ")]." + field);
        assertFalse(values.isEmpty(), "channel " + channelId + " not present in snapshot");
        return values.get(0).longValue();
    }

    private boolean channelInSnapshot(String token, long channelId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/read-states").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<?> matches = JsonPath.read(result.getResponse().getContentAsString(),
                "$[?(@.channelId==" + channelId + ")]");
        return !matches.isEmpty();
    }

    private long everyoneRoleId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        @SuppressWarnings("unchecked")
        List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(), "$[?(@.kind=='EVERYONE')].id");
        return ids.get(0).longValue();
    }

    private void ack(String token, long channelId, Long lastReadMessageId) throws Exception {
        String body = lastReadMessageId == null ? "" : "{\"lastReadMessageId\":" + lastReadMessageId + "}";
        mockMvc.perform(post("/api/channels/" + channelId + "/read").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(body))
                .andExpect(status().isNoContent());
    }

    private void send(String token, long channelId, String content) throws Exception {
        mockMvc.perform(post("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"content\":" + jsonQuote(content) + "}"))
                .andExpect(status().isCreated());
    }

    private long createChannel(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"name\":\"" + name + "\",\"type\":\"TEXT\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, "supersecret")))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"supersecret\"}", username)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private static String jsonQuote(String value) {
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
