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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Message replies and forwards, REST-level: the reply quote in the created response and in the
 * channel history (including the deleted-original marker), the reply ping's effect on the
 * read-state mention counter (and its dedup against an explicit {@code @token}), the cross-channel
 * 404 of a reply target, and both forward modes (intra-instance canonical copy — trusted;
 * cross-instance snapshot — untrusted) with their gates (target SEND_MESSAGES, source
 * READ_MESSAGE_HISTORY) plus the {@code ?beforeId=} history cursor.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReplyForwardIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private oak.server.repository.UserRepository userRepository;

    @Autowired
    private oak.server.repository.RoleRepository roleRepository;

    @Test
    void reply_quotesOriginalInCreateResponseAndHistory_andPingsAuthor() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long originalId = postMessage(bobToken, channelId, "the original words");

        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken).contentType(JSON)
                        .content("{\"content\":\"a reply\",\"replyToId\":" + originalId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reply.messageId").value(originalId.intValue()))
                .andExpect(jsonPath("$.reply.authorUsername").value("bob"))
                .andExpect(jsonPath("$.reply.preview").value("the original words"))
                .andExpect(jsonPath("$.reply.deleted").value(false))
                .andReturn();
        // The reply ping is a USER mention of the original author, span-less (offsets -1).
        assertEquals(1, (int) JsonPath.read(result.getResponse().getContentAsString(), "$.mentions.length()"));
        Number pingedId = JsonPath.read(result.getResponse().getContentAsString(), "$.mentions[0].id");
        assertEquals(bobId.longValue(), pingedId.longValue());
        Number pingStart = JsonPath.read(result.getResponse().getContentAsString(), "$.mentions[0].start");
        assertEquals(-1, pingStart.intValue());

        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].reply.messageId").value(originalId.intValue()))
                .andExpect(jsonPath("$[0].reply").doesNotExist());
    }

    @Test
    void reply_targetInAnotherChannel_isNotFound() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelA = createChannel(aliceToken, "a", "TEXT");
        Long channelB = createChannel(aliceToken, "b", "TEXT");
        Long foreignId = postMessage(aliceToken, channelB, "lives in b");

        mockMvc.perform(post("/api/channels/" + channelA + "/messages")
                        .header("Authorization", "Bearer " + aliceToken).contentType(JSON)
                        .content("{\"content\":\"wrong channel\",\"replyToId\":" + foreignId + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reply_survivesOriginalDeletion_asDeletedMarker() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long originalId = postMessage(bobToken, channelId, "to be deleted");
        postMessage(aliceToken, channelId, "replying", originalId);

        mockMvc.perform(delete("/api/channels/" + channelId + "/messages/" + originalId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reply.deleted").value(true))
                .andExpect(jsonPath("$[0].reply.preview").doesNotExist());
    }

    @Test
    void readState_unreadReplyCountsAsMention_plainMessageDoesNot() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        // Bob's registration seeded his read floor to the archive as of that moment; everything
        // posted after counts as unread for him.
        Long bobMessageId = postMessage(bobToken, channelId, "bob talks");

        // A plain message from alice: unread grows, no mention.
        postMessage(aliceToken, channelId, "plain words");
        assertEquals(0L, mentionCount(bobToken, channelId));

        // A REPLY from alice to bob's message: the synthesized ping makes it a mention.
        postMessage(aliceToken, channelId, "a reply", bobMessageId);
        assertEquals(1L, mentionCount(bobToken, channelId));

        // A reply whose content also carries an explicit "@bob": still ONE mention, not two.
        postMessage(aliceToken, channelId, "@bob and reply", bobMessageId);
        assertEquals(2L, mentionCount(bobToken, channelId));
    }

    @Test
    void forward_intraInstanceCopiesCanonicalOriginAndAttachment() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long sourceChannelId = createChannel(aliceToken, "source", "TEXT");
        Long targetChannelId = createChannel(aliceToken, "target", "TEXT");
        Long sourceId = postAttachmentMessage(bobToken, sourceChannelId, "forward me",
                "attachments/abc/pic.png", "pic.png");

        mockMvc.perform(post("/api/channels/" + targetChannelId + "/messages/forward")
                        .header("Authorization", "Bearer " + aliceToken).contentType(JSON)
                        .content("{\"sourceMessageId\":" + sourceId
                                + ",\"originServer\":\"Fake\",\"originChannel\":\"fake\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("forward me"))
                .andExpect(jsonPath("$.attachmentFileName").value("pic.png"))
                .andExpect(jsonPath("$.authorUsername").value("alice"))
                .andExpect(jsonPath("$.forward.serverName").value("OAK Server"))
                .andExpect(jsonPath("$.forward.channelName").value("source"))
                .andExpect(jsonPath("$.forward.authorUsername").value("bob"))
                .andExpect(jsonPath("$.forward.trusted").value(true))
                .andExpect(jsonPath("$.reply").doesNotExist());

        mockMvc.perform(get("/api/channels/" + targetChannelId + "/messages")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].forward.channelName").value("source"))
                .andExpect(jsonPath("$[0].forward.trusted").value(true));
    }

    @Test
    void forward_snapshotModeIsUntrusted_andAttachmentOnlyWorks() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long targetChannelId = createChannel(aliceToken, "target", "TEXT");

        mockMvc.perform(post("/api/channels/" + targetChannelId + "/messages/forward")
                        .header("Authorization", "Bearer " + aliceToken).contentType(JSON)
                        .content("{\"content\":\"from far away\",\"originServer\":\"Other Server\","
                                + "\"originChannel\":\"their-channel\",\"originAuthorName\":\"carol\","
                                + "\"originCreatedAt\":\"2026-02-02T12:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("from far away"))
                .andExpect(jsonPath("$.forward.serverName").value("Other Server"))
                .andExpect(jsonPath("$.forward.trusted").value(false));

        // Attachment-only snapshot (no content) is a legal forward.
        mockMvc.perform(post("/api/channels/" + targetChannelId + "/messages/forward")
                        .header("Authorization", "Bearer " + aliceToken).contentType(JSON)
                        .content("{\"attachmentKey\":\"attachments/x/y.png\","
                                + "\"attachmentFileName\":\"y.png\",\"originServer\":\"Other Server\","
                                + "\"originChannel\":\"their-channel\",\"originAuthorName\":\"carol\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentFileName").value("y.png"));
    }

    @Test
    void forward_gates_targetSendMessages_andSourceReadHistory() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long everyoneId = roleRepository
                .findByKind(oak.server.entity.RoleKind.EVERYONE).orElseThrow().getId();

        // Target channel denies SEND_MESSAGES to @everyone → bob's forward 403.
        Long sourceChannelId = createChannel(aliceToken, "source", "TEXT");
        Long targetChannelId = createChannel(aliceToken, "no-write", "TEXT");
        mockMvc.perform(put("/api/channels/" + targetChannelId + "/overrides/" + everyoneId)
                        .header("Authorization", "Bearer " + aliceToken).contentType(JSON)
                        .content("{\"allow\":[],\"deny\":[\"SEND_MESSAGES\"]}"))
                .andExpect(status().isOk());
        Long sourceId = postMessage(aliceToken, sourceChannelId, "hello");
        mockMvc.perform(post("/api/channels/" + targetChannelId + "/messages/forward")
                        .header("Authorization", "Bearer " + bobToken).contentType(JSON)
                        .content("{\"sourceMessageId\":" + sourceId + "}"))
                .andExpect(status().isForbidden());

        // Source channel denies READ_MESSAGE_HISTORY → the canonical copy is refused even though
        // the target is writable.
        Long secretChannelId = createChannel(aliceToken, "secret", "TEXT");
        mockMvc.perform(put("/api/channels/" + secretChannelId + "/overrides/" + everyoneId)
                        .header("Authorization", "Bearer " + aliceToken).contentType(JSON)
                        .content("{\"allow\":[],\"deny\":[\"READ_MESSAGE_HISTORY\"]}"))
                .andExpect(status().isOk());
        Long secretId = postMessage(aliceToken, secretChannelId, "private");
        mockMvc.perform(post("/api/channels/" + sourceChannelId + "/messages/forward")
                        .header("Authorization", "Bearer " + bobToken).contentType(JSON)
                        .content("{\"sourceMessageId\":" + secretId + "}"))
                .andExpect(status().isForbidden());

        // A snapshot forward into the readable target still works for bob (no source read needed).
        mockMvc.perform(post("/api/channels/" + sourceChannelId + "/messages/forward")
                        .header("Authorization", "Bearer " + bobToken).contentType(JSON)
                        .content("{\"content\":\"snapshot\",\"originServer\":\"Elsewhere\","
                                + "\"originChannel\":\"c\",\"originAuthorName\":\"carol\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void historyCursor_beforeIdPagesStrictlyOlderMessages() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long first = postMessage(aliceToken, channelId, "m1");
        Long second = postMessage(aliceToken, channelId, "m2");
        Long third = postMessage(aliceToken, channelId, "m3");
        postMessage(aliceToken, channelId, "m4");
        postMessage(aliceToken, channelId, "m5");

        MvcResult result = mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .param("beforeId", String.valueOf(third))
                        .param("limit", "2")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();
        List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(), "$[*].id");
        assertEquals(first.longValue(), ids.get(0).longValue());
        assertEquals(second.longValue(), ids.get(1).longValue());
        assertTrue(ids.stream().noneMatch(id -> id.longValue() == third.longValue()));
    }

    // ---- seeding helpers (REST-driven, mirrors ReactionIntegrationTest) ----

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isCreated());
        return login(username, password);
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

    private Long postMessage(String token, Long channelId, String content) throws Exception {
        return postMessage(token, channelId, content, null);
    }

    private Long postMessage(String token, Long channelId, String content, Long replyToId) throws Exception {
        String body = replyToId == null
                ? String.format("{\"content\":\"%s\"}", content)
                : String.format("{\"content\":\"%s\",\"replyToId\":%d}", content, replyToId);
        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** Attachment-bearing message without any upload — the test signer is a noop, only the key matters. */
    private Long postAttachmentMessage(String token, Long channelId, String content,
                                       String attachmentKey, String attachmentFileName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format(
                                "{\"content\":\"%s\",\"attachmentKey\":\"%s\",\"attachmentFileName\":\"%s\"}",
                                content, attachmentKey, attachmentFileName)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** The channel's mentionCount from the read-state snapshot (0 when the channel has no entry). */
    private long mentionCount(String token, Long channelId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/read-states")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        List<Number> counts = JsonPath.read(result.getResponse().getContentAsString(),
                "$[?(@.channelId==" + channelId + ")].mentionCount");
        return counts.isEmpty() ? 0L : counts.get(0).longValue();
    }
}
