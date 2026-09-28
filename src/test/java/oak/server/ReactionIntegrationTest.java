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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Emoji reactions on messages: the idempotent PUT/DELETE add/remove pair (both answering 200 with
 * the full deterministic snapshot), the slug whitelist (400), the 404 scoping of cross-channel
 * message ids, the ADD_REACTIONS gate (channel-override deny → 403, OWNER bypass → 200; in a
 * DM the same gate degenerates to the participant check — a third user gets 403), the FK cleanup
 * on message delete, and the wire contract that an edit push still carries the reaction snapshot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReactionIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private oak.server.repository.UserRepository userRepository;

    @Autowired
    private oak.server.repository.RoleRepository roleRepository;

    @Autowired
    private oak.server.repository.MessageReactionRepository reactionRepository;

    @Autowired
    private oak.server.service.RoleSeeder roleSeeder;

    @Test
    void addReaction_returnsSnapshotAndPersistsInHistory() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long aliceId = userRepository.findByUsername("alice").orElseThrow().getId();
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "hello world");

        mockMvc.perform(put(reactionUrl(channelId, messageId, "thumbsup"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reaction").value("thumbsup"))
                .andExpect(jsonPath("$[0].count").value(1))
                .andExpect(jsonPath("$[0].userIds[0]").value(aliceId.intValue()))
                .andExpect(jsonPath("$[0].usernames[0]").value("alice"));

        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reactions.length()").value(1))
                .andExpect(jsonPath("$[0].reactions[0].reaction").value("thumbsup"));
    }

    @Test
    void addReaction_isIdempotent() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "hello");

        for (int attempt = 1; attempt <= 2; attempt++) {
            mockMvc.perform(put(reactionUrl(channelId, messageId, "smile"))
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].count").value(1));
        }
    }

    @Test
    void twoUsersReactingWithTheSameSlug_aggregateIntoOneSummaryWithBothIds() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long aliceId = userRepository.findByUsername("alice").orElseThrow().getId();
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "react to me");

        mockMvc.perform(put(reactionUrl(channelId, messageId, "hundred"))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(put(reactionUrl(channelId, messageId, "hundred"))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].count").value(2))
                .andExpect(jsonPath("$[0].userIds.length()").value(2))
                .andExpect(jsonPath("$[0].usernames.length()").value(2));
        assertTrue(reactionRepository.findByMessageId(messageId).size() == 2);
    }

    @Test
    void removeReaction_returnsEmptySnapshotAndIsIdempotent() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "hello");

        mockMvc.perform(put(reactionUrl(channelId, messageId, "eyes"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete(reactionUrl(channelId, messageId, "eyes"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // deleting a row that is already gone is still 200 with the same snapshot
        mockMvc.perform(delete(reactionUrl(channelId, messageId, "eyes"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownSlug_rejectedWith400() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "hello");

        mockMvc.perform(put(reactionUrl(channelId, messageId, "elephant"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
        assertTrue(reactionRepository.findByMessageId(messageId).isEmpty());
    }

    @Test
    void lookupGuards_unknownChannelMessageAndCrossChannelAll404() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "hello");
        Long otherChannelId = createChannel(aliceToken, "random", "TEXT");

        mockMvc.perform(put(reactionUrl(channelId, 999999L, "smile"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(reactionUrl(999999L, messageId, "smile"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        // the message id belongs to ANOTHER channel — no cross-channel reach
        mockMvc.perform(put(reactionUrl(otherChannelId, messageId, "smile"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void channelOverrideDenyingAddReactions_plainMember403_ownerBypasses() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "locked", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "react carefully");
        Long everyoneId = roleRepository
                .findByKind(oak.server.entity.RoleKind.EVERYONE).orElseThrow().getId();

        mockMvc.perform(put("/api/channels/" + channelId + "/overrides/" + everyoneId)
                        .header("Authorization", "Bearer " + ownerToken).contentType(JSON)
                        .content("{\"allow\":[],\"deny\":[\"ADD_REACTIONS\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(put(reactionUrl(channelId, messageId, "thumbsup"))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        assertTrue(reactionRepository.findByMessageId(messageId).isEmpty());

        // OWNER (ADMINISTRATOR) bypasses the override
        mockMvc.perform(put(reactionUrl(channelId, messageId, "thumbsup"))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").value(1));
    }

    @Test
    void dm_participantsCanReact_thirdUserForbidden() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        String bobToken = login("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        String carolToken = registerAndLogin("carol", "supersecret");

        Long dmId = createDm(aliceToken, bobId);
        Long messageId = postMessage(aliceToken, dmId, "alice's dm words");

        mockMvc.perform(put(reactionUrl(dmId, messageId, "heart"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").value(1));
        mockMvc.perform(put(reactionUrl(dmId, messageId, "heart"))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").value(2));

        // In a DM the ADD_REACTIONS check degenerates to the participant check — carol is out.
        mockMvc.perform(put(reactionUrl(dmId, messageId, "heart"))
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteMessage_clearsReactionRows() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "to be removed");
        mockMvc.perform(put(reactionUrl(channelId, messageId, "thumbsdown"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/channels/" + channelId + "/messages/" + messageId)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertTrue(reactionRepository.findByMessageId(messageId).isEmpty());
    }

    @Test
    void editMessage_stillCarriesReactionSnapshot() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "hello");
        mockMvc.perform(put(reactionUrl(channelId, messageId, "smile"))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/channels/" + channelId + "/messages/" + messageId)
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON).content("{\"content\":\"hello edited\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("hello edited"))
                .andExpect(jsonPath("$.reactions.length()").value(1))
                .andExpect(jsonPath("$.reactions[0].reaction").value("smile"));
    }

    @Test
    void historyWithoutReactions_reportsAnEmptyList() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        postMessage(aliceToken, channelId, "naked message");

        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reactions.length()").value(0));
    }

    @Test
    void everyoneAddReactionsSelfRepairsForDatabasesSeededBeforeTheBit() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret"); // the plebeian the gate is about
        String bobToken = login("bob", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "hello world");

        // Simulate the live failure (2026-09-29): a database seeded BEFORE
        // ADD_REACTIONS joined EVERYONE_DEFAULTS — the idempotent seeder never
        // touches an existing @everyone row, so ordinary members lost reactions
        // entirely (the owner bypasses via ADMINISTRATOR, which hid it from smoke).
        oak.server.entity.Role everyone = roleRepository.findByKind(oak.server.entity.RoleKind.EVERYONE).orElseThrow();
        everyone.setPermissions(everyone.getPermissions() & ~oak.server.entity.Permission.ADD_REACTIONS.bit);
        roleRepository.save(everyone);

        mockMvc.perform(put(reactionUrl(channelId, messageId, "thumbsup"))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // the boot-time repair restores the feature without manual SQL
        roleSeeder.ensureEveryoneAddReactions();
        mockMvc.perform(put(reactionUrl(channelId, messageId, "thumbsup"))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
    }

    // --- helpers (mirror MessageEditDeleteIntegrationTest) ---

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isCreated());
        return login(username, password);
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
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

    /** 1:1 DM get-or-create (friendship is not required for DMs). */
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

    private static String reactionUrl(Long channelId, Long messageId, String slug) {
        return "/api/channels/" + channelId + "/messages/" + messageId + "/reactions/" + slug;
    }
}
