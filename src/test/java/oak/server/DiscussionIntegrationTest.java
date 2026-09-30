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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Discussions (threads): the create gates (TEXT parent, origin in parent, SEND_MESSAGES, one
 * thread per message), the origin-snapshot wire fields, posting into the thread (delegated
 * permissions, auto-track "wrote once → follows", NOTHING in the parent's history), tracking,
 * /mine ordering, the close cascade (MANAGE_MESSAGES + full teardown), the exclusions (channel
 * list, read-state snapshot, search), and the parent-deletion teardown.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DiscussionIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestInvites testInvites;
    @Autowired
    private oak.server.repository.DiscussionRepository discussionRepository;
    @Autowired
    private oak.server.repository.ChannelRepository channelRepository;
    @Autowired
    private oak.server.repository.MessageRepository messageRepository;
    @Autowired
    private oak.server.repository.ChannelParticipantRepository participantRepository;
    @Autowired
    private oak.server.repository.UserRepository userRepository;

    @Test
    void createSnapshotFieldsAndCreatorTracking() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "the origin message");

        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/discussions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"messageId\":%d}", messageId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentChannelId").value(channelId))
                .andExpect(jsonPath("$.originMessageId").value(messageId))
                .andExpect(jsonPath("$.originSnippet").value("the origin message"))
                .andExpect(jsonPath("$.originAuthorName").value("alice"))
                .andExpect(jsonPath("$.tracked").value(true))
                .andExpect(jsonPath("$.replyCount").value(0))
                .andReturn();
        Long discussionId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

        // the channel-scoped listing sees it with the same snapshot
        mockMvc.perform(get("/api/channels/" + channelId + "/discussions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(discussionId));

        // duplicate → 409 (one thread per message)
        mockMvc.perform(post("/api/channels/" + channelId + "/discussions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"messageId\":%d}", messageId)))
                .andExpect(status().isConflict());
    }

    @Test
    void aTrackedDiscussionDoesNotLeakIntoTheDmList() throws Exception {
        // Live report 2026-09-24: a tracked discussion rode /api/channels/dm as a
        // phantom "DM with yourself" (its only channel_participants row is the
        // tracker's own, so the client fell back to rendering the SELF). The DM
        // list must carry DM/GROUP_DM channels only.
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long dmId = createDm(ownerToken, bobId);

        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "the origin message");
        startDiscussion(ownerToken, channelId, messageId); // auto-tracks the creator

        mockMvc.perform(get("/api/channels/dm").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(dmId))
                .andExpect(jsonPath("$[0].type").value("DM"));
    }

    @Test
    void createLookupAndTypeGuards() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "origin");
        Long otherChannelId = createChannel(ownerToken, "random", "TEXT");

        // an origin id from ANOTHER channel → 404 (no cross-channel reach)
        mockMvc.perform(post("/api/channels/" + otherChannelId + "/discussions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"messageId\":%d}", messageId)))
                .andExpect(status().isNotFound());
        // a VOICE parent → 400 (discussions start on text channels only)
        Long voiceId = createChannel(ownerToken, "lounge", "VOICE");
        mockMvc.perform(post("/api/channels/" + voiceId + "/discussions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"messageId\":%d}", messageId)))
                .andExpect(status().isBadRequest());
        // a DM parent → 400 as well
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long dmId = createDm(ownerToken, bobId);
        mockMvc.perform(post("/api/channels/" + dmId + "/discussions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"messageId\":%d}", messageId)))
                .andExpect(status().isBadRequest());
        // unknown channel → 404
        mockMvc.perform(post("/api/channels/999999/discussions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"messageId\":%d}", messageId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void postingIntoTheThreadAutoTracksAndNeverTouchesTheParentHistory() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        String bobToken = login("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "origin");
        Long discussionId = startDiscussion(ownerToken, channelId, messageId);

        // a plain member (SEND via @everyone defaults, resolved through the PARENT) posts into
        // the thread — delegated permissions need no setup
        mockMvc.perform(post("/api/channels/" + discussionId + "/messages")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON)
                        .content("{\"content\":\"first reply\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channelId").value(discussionId));

        // "wrote once → follows": bob's /mine lists the thread he never explicitly tracked
        mockMvc.perform(get("/api/discussions/mine")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(discussionId))
                .andExpect(jsonPath("$[0].tracked").value(true))
                .andExpect(jsonPath("$[0].replyCount").value(1));

        // the parent's history is untouched by the reply
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // the thread's own history carries it
        mockMvc.perform(get("/api/channels/" + discussionId + "/messages")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("first reply"));
    }

    @Test
    void mineOrdersByRecentActivity() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long firstOrigin = postMessage(ownerToken, channelId, "first origin");
        Long secondOrigin = postMessage(ownerToken, channelId, "second origin");
        Long firstDiscussion = startDiscussion(ownerToken, channelId, firstOrigin);
        Long secondDiscussion = startDiscussion(ownerToken, channelId, secondOrigin);

        // post into the FIRST thread — it becomes the most recently active
        mockMvc.perform(post("/api/channels/" + firstDiscussion + "/messages")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"content\":\"revival\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/discussions/mine")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(firstDiscussion))
                .andExpect(jsonPath("$[1].id").value(secondDiscussion));
    }

    @Test
    void trackUntrackAreIdempotentAndPersonal() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        String bobToken = login("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "origin");
        Long discussionId = startDiscussion(ownerToken, channelId, messageId);

        // bob tracks twice, untracks twice — all 204, exactly one participant row for him
        mockMvc.perform(put(trackUrl(discussionId)).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(trackUrl(discussionId)).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/discussions/mine").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(delete(trackUrl(discussionId)).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(trackUrl(discussionId)).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/discussions/mine").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // personal only: alice (the creator) still tracks hers
        mockMvc.perform(get("/api/discussions/mine").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void lazyCreateWithTheFirstReplyCreatesThreadAndMessageTogether() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "origin");

        // the draft's first reply carries the content: ONE call creates the thread AND its
        // first message (the user rule — "Start discussion" alone creates nothing)
        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/discussions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"messageId\":%d,\"content\":\"the first reply\"}", messageId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replyCount").value(1))
                .andExpect(jsonPath("$.tracked").value(true))
                .andReturn();
        Long discussionId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

        // the message lives in the THREAD only
        mockMvc.perform(get("/api/channels/" + discussionId + "/messages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("the first reply"));
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // a CONFLICT with a first message aboard reuses the winner's thread — never a 409
        register("bob", "supersecret");
        String bobToken = login("bob", "supersecret");
        mockMvc.perform(post("/api/channels/" + channelId + "/discussions")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON)
                        .content(String.format("{\"messageId\":%d,\"content\":\"raced reply\"}", messageId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(discussionId))
                .andExpect(jsonPath("$.replyCount").value(2));
    }

    @Test
    void aMentionInsideAThreadSubscribesTheMentionedUser() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("carol", "supersecret");
        String carolToken = login("carol", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "origin");
        Long discussionId = startDiscussion(ownerToken, channelId, messageId);

        // carol never touched the thread; the @mention inside it subscribes her
        mockMvc.perform(post("/api/channels/" + discussionId + "/messages")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"content\":\"hey @carol take a look\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/discussions/mine").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(discussionId))
                .andExpect(jsonPath("$[0].tracked").value(true));
    }

    @Test
    void startingAThreadOnSomeoneElsesMessageAutoTracksTheOriginAuthor() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("carol", "supersecret");
        String carolToken = login("carol", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        // CAROL wrote the origin; ALICE starts the thread on it
        Long originId = postMessage(carolToken, channelId, "carol's origin message");
        Long discussionId = startDiscussion(ownerToken, channelId, originId);

        // the origin author never touched the thread, yet it is in her "mine" — the
        // participant row from create() (the first-reply mention ping depends on it)
        mockMvc.perform(get("/api/discussions/mine").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(discussionId))
                .andExpect(jsonPath("$[0].tracked").value(true));
        // and the starter tracks her own thread too, of course
        mockMvc.perform(get("/api/discussions/mine").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void closeRequiresManageMessagesAndTearsEverythingDown() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        String bobToken = login("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "origin");
        Long discussionId = startDiscussion(ownerToken, channelId, messageId);
        postMessageInto(discussionId, ownerToken, "reply with an attachment-less body");
        mockMvc.perform(put(trackUrl(discussionId)).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());

        // a plain member cannot close
        mockMvc.perform(delete("/api/discussions/" + discussionId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        assertTrue(discussionRepository.findByChannelId(discussionId).isPresent());

        // MANAGE_MESSAGES (resolved through the parent) can
        Long roleId = createRole(ownerToken, "Closer", "MANAGE_MESSAGES");
        assignRoles(ownerToken, bobId, roleId);
        mockMvc.perform(delete("/api/discussions/" + discussionId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());

        // the FULL teardown: discussion row, channel row, messages, participants
        assertTrue(discussionRepository.findByChannelId(discussionId).isEmpty());
        assertTrue(channelRepository.findById(discussionId).isEmpty());
        assertEquals(0, messageRepository.countByChannelId(discussionId));
        assertEquals(0, participantRepository.findByChannelId(discussionId).size());
        // the channel-scoped listing is empty again, and the parent's message survives
        mockMvc.perform(get("/api/channels/" + channelId + "/discussions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertTrue(messageRepository.findByIdAndChannelId(messageId, channelId).isPresent());
    }

    @Test
    void discussionsAreExcludedFromChannelsListReadStatesAndSearch() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "origin with a needle");
        Long discussionId = startDiscussion(ownerToken, channelId, messageId);
        postMessageInto(discussionId, ownerToken, "a needle in the thread");

        // GET /api/channels never lists the discussion channel
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(result -> assertFalse(
                        result.getResponse().getContentAsString().contains("\"id\":" + discussionId + ","),
                        "the discussion channel must not appear in the channel list"));

        // the read-state snapshot carries no entry for the discussion channel
        mockMvc.perform(get("/api/read-states").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(result -> assertFalse(
                        result.getResponse().getContentAsString().contains("\"channelId\":" + discussionId),
                        "discussions are read-state-silent"));

        // channel-scoped search on a discussion → 400; server-wide search walks TEXT channels only
        mockMvc.perform(get("/api/search/messages?q=needle&channelId=" + discussionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
        MvcResult search = mockMvc.perform(get("/api/search/messages?q=needle")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(search.getResponse().getContentAsString().contains("origin with a needle"));
        assertFalse(search.getResponse().getContentAsString().contains("a needle in the thread"));
    }

    @Test
    void deletingTheParentChannelRemovesItsDiscussions() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "origin");
        Long discussionId = startDiscussion(ownerToken, channelId, messageId);
        postMessageInto(discussionId, ownerToken, "reply");

        mockMvc.perform(delete("/api/channels/" + channelId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        assertTrue(discussionRepository.findByChannelId(discussionId).isEmpty());
        assertTrue(channelRepository.findById(discussionId).isEmpty());
        assertEquals(0, messageRepository.countByChannelId(discussionId));
    }

    // --- helpers ---

    private String registerAndLogin(String username, String password) throws Exception {
        register(username, password);
        return login(username, password);
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
    }

    private String login(String username, String password) throws Exception {
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

    private Long createDm(String token, Long peerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels/dm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"recipientId\":%d}", peerId)))
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

    private void postMessageInto(Long channelId, String token, String content) throws Exception {
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"content\":\"%s\"}", content)))
                .andExpect(status().isCreated());
    }

    private Long startDiscussion(String token, Long channelId, Long messageId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/discussions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"messageId\":%d}", messageId)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long createRole(String token, String name, String permission) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"permissions\":[\"%s\"]}", name, permission)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void assignRoles(String token, Long userId, Long roleId) throws Exception {
        mockMvc.perform(put("/api/members/" + userId + "/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"roleIds\":[" + roleId + "]}"))
                .andExpect(status().isOk());
    }

    private static String trackUrl(Long discussionId) {
        return "/api/discussions/" + discussionId + "/track";
    }
}
