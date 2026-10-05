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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Edit/delete of messages: the author-or-moderator gate (MANAGE_MESSAGES for others' messages in
 * server channels; author-only in DMs — the DM branch of hasInChannel grants every permission to
 * any participant, so the author check there must be explicit), editedAt stamping, and the
 * 404-scoping of a message id that belongs to another channel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MessageEditDeleteIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    @Autowired
    private oak.server.repository.UserRepository userRepository;

    @Test
    void editOwnMessage_updatesContentStampsEditedAt_keepsCreatedAt() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "hello world");

        MvcResult result = mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON)
                        .content("{\"content\":\"hello edited world\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("hello edited world"))
                .andExpect(jsonPath("$.editedAt").exists())
                .andReturn();
        String createdAt = JsonPath.read(result.getResponse().getContentAsString(), "$.createdAt");

        // history reflects the new text; createdAt unchanged, editedAt visible
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("hello edited world"))
                .andExpect(jsonPath("$[0].createdAt").value(createdAt))
                .andExpect(jsonPath("$[0].editedAt").exists());
    }

    @Test
    void editRemovesAttachmentsSubsetRejectsForeignAndEmpty() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessageWithAttachments(aliceToken, channelId, "pics",
                java.util.List.of("attachments/a", "attachments/b"));

        // remove "attachments/a" — ONE PATCH carrying the surviving subset; the response carries the key
        mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON)
                        .content("{\"content\":\"one pic now\",\"attachments\":[{\"key\":\"attachments/b\",\"fileName\":\"b.png\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("one pic now"))
                .andExpect(jsonPath("$.editedAt").exists())
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.attachments[0].key").value("attachments/b"))
                .andExpect(jsonPath("$.attachments[0].fileName").value("b.png"));

        // history reflects the removal
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachments.length()").value(1))
                .andExpect(jsonPath("$[0].attachments[0].key").value("attachments/b"));

        // a foreign key (not currently attached) -> 400
        mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON)
                        .content("{\"content\":\"x\",\"attachments\":[{\"key\":\"attachments/zzz\",\"fileName\":\"z.png\"}]}"))
                .andExpect(status().isBadRequest());
        // a duplicated key -> 400
        mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON)
                        .content("{\"content\":\"x\",\"attachments\":[{\"key\":\"attachments/b\",\"fileName\":\"b.png\"},"
                                + "{\"key\":\"attachments/b\",\"fileName\":\"b.png\"}]}"))
                .andExpect(status().isBadRequest());
        // stripping the LAST attachment while blanking the text -> 400 (the resulting message
        // would be empty)
        mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON)
                        .content("{\"content\":\"   \",\"attachments\":[]}"))
                .andExpect(status().isBadRequest());
        // a PATCH without the attachments field leaves them untouched
        mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON)
                        .content("{\"content\":\"one pic again\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()").value(1));
    }

    @Test
    void editOthersMessage_requiresManageMessages() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "alice's words");
        String bobToken = login("bob", "supersecret");

        // plain member -> 403, content untouched
        mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON)
                        .content("{\"content\":\"bob was here\"}"))
                .andExpect(status().isForbidden());
        assertHistoryContent(ownerToken, channelId, "alice's words");

        // granted MANAGE_MESSAGES -> 200
        Long roleId = createRole(ownerToken, "Moderator", "MANAGE_MESSAGES");
        assignRoles(ownerToken, bobId, roleId);
        mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON)
                        .content("{\"content\":\"moderated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("moderated"));

        // OWNER (ADMINISTRATOR bypass) may edit too
        mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"content\":\"owner rewrote it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("owner rewrote it"));
    }

    @Test
    void edit_validationAndLookupGuards() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "hello");

        // blank content on a text-only message -> 400 (the service's content-or-attachment
        // rule — the bean-level @NotBlank was relaxed for attachment-only edits)
        mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON).content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());
        // empty body -> the resulting message would have neither text nor attachments -> 400
        mockMvc.perform(patch(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest());
        // unknown message -> 404
        mockMvc.perform(patch(messageUrl(channelId, 999999L))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON).content("{\"content\":\"x\"}"))
                .andExpect(status().isNotFound());
        // unknown channel -> 404
        mockMvc.perform(patch(messageUrl(999999L, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON).content("{\"content\":\"x\"}"))
                .andExpect(status().isNotFound());
        // a message id that lives in ANOTHER channel -> 404 (no cross-channel reach)
        Long otherChannelId = createChannel(aliceToken, "random", "TEXT");
        mockMvc.perform(patch(messageUrl(otherChannelId, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON).content("{\"content\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteOwnMessage_removesItFromHistory() throws Exception {
        String aliceToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(aliceToken, "general", "TEXT");
        Long messageId = postMessage(aliceToken, channelId, "to be removed");

        mockMvc.perform(delete(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deleteOthersMessage_requiresManageMessages() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "alice's words");
        String bobToken = login("bob", "supersecret");

        // plain member -> 403, message survives
        mockMvc.perform(delete(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        assertHistoryLength(ownerToken, channelId, 1);

        // with MANAGE_MESSAGES -> 204
        Long roleId = createRole(ownerToken, "Cleaner", "MANAGE_MESSAGES");
        assignRoles(ownerToken, bobId, roleId);
        mockMvc.perform(delete(messageUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());
        assertHistoryLength(ownerToken, channelId, 0);

        // unknown message -> 404
        mockMvc.perform(delete(messageUrl(channelId, 999999L))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void dm_authorCanEditAndDeleteOwn_nonAuthorParticipantCannot() throws Exception {
        // The key DM case: hasInChannel returns participant-true for ANY permission in a DM, so
        // the author check must be explicit — otherwise any participant could rewrite the peer.
        String aliceToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        String bobToken = login("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();

        Long dmId = createDm(aliceToken, bobId);
        Long messageId = postMessage(aliceToken, dmId, "alice's dm words");

        // author edits own -> 200
        mockMvc.perform(patch(messageUrl(dmId, messageId))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(JSON).content("{\"content\":\"edited dm words\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("edited dm words"));

        // the other participant (a legitimate DM reader) edits/deletes alice's message -> 403
        mockMvc.perform(patch(messageUrl(dmId, messageId))
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"content\":\"bob rewrote alice\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(messageUrl(dmId, messageId))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // author deletes own -> 204
        mockMvc.perform(delete(messageUrl(dmId, messageId))
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        assertHistoryLength(aliceToken, dmId, 0);
    }

    // --- helpers ---

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
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

    /** Posts a message carrying attachment refs (arbitrary non-blank keys — the test signer is a
     *  noop, only the key matters). */
    private Long postMessageWithAttachments(String token, Long channelId, String content,
                                            java.util.List<String> keys) throws Exception {
        StringBuilder refs = new StringBuilder();
        for (int keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
            if (keyIndex > 0) {
                refs.append(',');
            }
            refs.append(String.format("{\"key\":\"%s\",\"fileName\":\"%d.png\"}", keys.get(keyIndex), keyIndex));
        }
        MvcResult result = mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"content\":\"%s\",\"attachments\":[%s]}", content, refs)))
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

    private static String messageUrl(Long channelId, Long messageId) {
        return "/api/channels/" + channelId + "/messages/" + messageId;
    }

    private void assertHistoryContent(String token, Long channelId, String expected) throws Exception {
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value(expected));
    }

    private void assertHistoryLength(String token, Long channelId, int expected) throws Exception {
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(expected));
    }
}
