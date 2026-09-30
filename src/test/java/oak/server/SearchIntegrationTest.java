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
import oak.server.entity.Channel;
import oak.server.entity.ChannelRoleOverride;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end search + history-cursor flow through MockMvc on the in-memory H2 test profile.
 * Covers: substring matching (case-insensitive ASCII, same-case Cyrillic — case FOLDING of
 * Cyrillic is a live-Postgres concern, not H2's), LIKE-metacharacter escaping, attachment
 * file-name hits, author filter, paging cursor + constant total, the q/author 400, access
 * gates (hidden channel, DM participant, server-wide never leaking DMs), and the
 * aroundId/afterId history cursors (the search-result jump) including the ack-monotonicity
 * hardening.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SearchIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestInvites testInvites;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private ChannelRepository channelRepository;
    @Autowired
    private ChannelRoleOverrideRepository channelRoleOverrideRepository;

    @Test
    void searchWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/search/messages").param("q", "hello"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void channelSearchMatchesSubstringsAndPagesWithConstantTotal() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        postMessage(aliceToken, channelId, "hello foo");
        postMessage(bobToken, channelId, "HELLO world");
        postMessage(aliceToken, channelId, "bye now");
        postMessage(bobToken, channelId, "say hello again");

        // Case-insensitive substring: "hello" matches all three hello rows, newest first.
        MvcResult pageOne = mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("q", "HeLLo")
                        .param("channelId", String.valueOf(channelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].content").value("say hello again"))
                .andExpect(jsonPath("$.results[1].content").value("HELLO world"))
                .andExpect(jsonPath("$.results[2].content").value("hello foo"))
                .andReturn();
        Long oldestHitId = ((Number) JsonPath.read(pageOne.getResponse().getContentAsString(),
                "$.results[2].id")).longValue();

        // Cursor page with the same total.
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("q", "hello")
                        .param("channelId", String.valueOf(channelId))
                        .param("beforeId", String.valueOf(oldestHitId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.results.length()").value(0));

        // Same-case Cyrillic substring (case folding is a live-smoke concern, not H2's).
        postMessage(aliceToken, channelId, "привет миру");
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("q", "привет")
                        .param("channelId", String.valueOf(channelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].content").value("привет миру"));
    }

    @Test
    void likeMetacharactersAreEscaped() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        postMessage(aliceToken, channelId, "task 50_x done");
        postMessage(aliceToken, channelId, "task 50ax done");

        // Without escaping, "50_" would match BOTH rows (underscore = any single char).
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("q", "50_")
                        .param("channelId", String.valueOf(channelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].content").value("task 50_x done"));

        // The plain substring still matches both.
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("q", "50")
                        .param("channelId", String.valueOf(channelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void attachmentFileNameMatches() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON)
                        .content("{\"content\":null,\"attachments\":[{\"key\":\"attachments/u1/quarterly.pdf\",\"fileName\":\"quarterly.pdf\"}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("q", "quarterly")
                        .param("channelId", String.valueOf(channelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].attachments[0].fileName").value("quarterly.pdf"));
    }

    @Test
    void authorFilterNarrowsAndUnknownAuthorIsEmpty() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        postMessage(aliceToken, channelId, "gamma update");
        postMessage(bobToken, channelId, "gamma update too");

        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("q", "gamma")
                        .param("author", "bob")
                        .param("channelId", String.valueOf(channelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].authorUsername").value("bob"));

        // Author-only search (no q): everything by alice.
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("author", "alice")
                        .param("channelId", String.valueOf(channelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].authorUsername").value("alice"));

        // Unknown author: empty result, not 404 — no oracle for probing logins.
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("author", "ghost")
                        .param("channelId", String.valueOf(channelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // Neither q nor author: 400.
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchRespectsChannelAndServerWideGates() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String outsiderToken = registerAndLogin("carol", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");

        // A hidden channel (the @everyone VIEW deny): its hits are invisible server-wide to the
        // outsider, visible to the owner; the channel-scoped search 403s for the outsider.
        Long secretId = createChannel(ownerToken, "secret", "TEXT");
        postMessage(ownerToken, secretId, "needle in the secret channel");
        Channel secret = channelRepository.findById(secretId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(secret, everyone, 0L, Permission.VIEW_CHANNEL.bit));

        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .param("q", "needle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("q", "needle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .param("q", "needle")
                        .param("channelId", String.valueOf(secretId)))
                .andExpect(status().isForbidden());

        // DMs: a channel-scoped DM search works for a participant and 403s for anyone else;
        // the server-wide search NEVER returns DM content — not even to a participant.
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long dmId = createDm(ownerToken, bobId);
        postMessage(ownerToken, dmId, "dmneedle private");
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("q", "dmneedle")
                        .param("channelId", String.valueOf(dmId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .param("q", "dmneedle")
                        .param("channelId", String.valueOf(dmId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("q", "dmneedle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // The public channel's hits still show up server-wide for both (bob's token unused otherwise).
        mockMvc.perform(get("/api/search/messages")
                        .header("Authorization", "Bearer " + bobToken)
                        .param("q", "needle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void aroundAndAfterCursorsWindowTheHistory() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            ids.add(postMessage(aliceToken, channelId, "entry " + index));
        }
        Long otherChannelId = createChannel(aliceToken, "other", "TEXT");
        postMessage(aliceToken, otherChannelId, "other channel message");

        // aroundId in the middle with limit=50: 24 older + target + 25 newer.
        Long target = ids.get(29);
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("aroundId", String.valueOf(target))
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50))
                .andExpect(jsonPath("$[0].id").value(ids.get(5).intValue()))
                .andExpect(jsonPath("$[24].id").value(target.intValue()))
                .andExpect(jsonPath("$[49].id").value(ids.get(54).intValue()));

        // aroundId near the start: short older half, no error.
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("aroundId", String.valueOf(ids.get(1)))
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(27))
                .andExpect(jsonPath("$[0].id").value(ids.get(0).intValue()))
                .andExpect(jsonPath("$[1].id").value(ids.get(1).intValue()))
                .andExpect(jsonPath("$[26].id").value(ids.get(26).intValue()));

        // afterId pages strictly newer, ascending.
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("afterId", String.valueOf(ids.get(5)))
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].id").value(ids.get(6).intValue()))
                .andExpect(jsonPath("$[9].id").value(ids.get(15).intValue()));
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("afterId", String.valueOf(ids.get(55)))
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[3].id").value(ids.get(59).intValue()));

        // Cross-channel aroundId 404s (no leak); combining cursors is a 400.
        mockMvc.perform(get("/api/channels/" + otherChannelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("aroundId", String.valueOf(target)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .param("aroundId", String.valueOf(target))
                        .param("beforeId", String.valueOf(ids.get(10))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ackNeverMovesTheReadPointerBackwards() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        postMessage(aliceToken, channelId, "first");
        postMessage(aliceToken, channelId, "second");
        MvcResult history = mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk()).andReturn();
        List<Number> idNumbers = JsonPath.read(history.getResponse().getContentAsString(), "$[*].id");
        Long olderId = idNumbers.get(0).longValue();
        Long newerId = idNumbers.get(1).longValue();

        // Read up to the newest, then a stale/partial-window ack (an around-jump client)
        // must NOT rewind the pointer and resurrect unread.
        mockMvc.perform(post("/api/channels/" + channelId + "/read")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON).content("{\"lastReadMessageId\":" + newerId + "}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/channels/" + channelId + "/read")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON).content("{\"lastReadMessageId\":" + olderId + "}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/read-states")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(String.format("$[?(@.channelId==%d)].unreadCount", channelId)).value(0))
                .andExpect(jsonPath(String.format("$[?(@.channelId==%d)].lastReadMessageId", channelId)).value(newerId.intValue()));
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

    private Long createDm(String token, Long recipientId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels/dm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"recipientId\":%d}", recipientId)))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long postMessage(String token, Long channelId, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"content\":\"%s\"}", content)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
