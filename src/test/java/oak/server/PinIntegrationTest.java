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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Channel pins (Discord-style): the MANAGE_MESSAGES gate (any participant in a DM — the DM
 * branch of hasInChannel grants every permission), the per-channel cap of 50 (the 51st distinct
 * pin is a 400), the idempotency of pin/unpin, the wire fields (pinned/pinnedBy/pinnedAt —
 * appended at the record's end, paired deploy), the literal GET /pinned route, and the
 * 404-scoping of a message id from another channel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PinIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private oak.server.repository.UserRepository userRepository;

    @Test
    void pinUnpin_roundTripListAndWireFields() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "important stuff");

        // unpinned wire defaults: pinned=false, no pinner, no moment
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pinned").value(false))
                .andExpect(jsonPath("$[0].pinnedBy").doesNotExist())
                .andExpect(jsonPath("$[0].pinnedAt").doesNotExist());

        // pin -> 200 with the full row; pinnedBy is the PINNER's name, not the author's
        mockMvc.perform(put(pinUrl(channelId, messageId)))
                .andExpect(status().isUnauthorized());
        MvcResult pinResult = mockMvc.perform(put(pinUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(true))
                .andExpect(jsonPath("$.pinnedBy").value("alice"))
                .andExpect(jsonPath("$.pinnedAt").exists())
                .andReturn();
        String pinnedAt = JsonPath.read(pinResult.getResponse().getContentAsString(), "$.pinnedAt");

        // re-pin is idempotent: the ORIGINAL pinner/moment survive (no attribution steal)
        Thread.sleep(5); // ensure a would-be re-stamp would differ
        mockMvc.perform(put(pinUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinnedBy").value("alice"))
                .andExpect(jsonPath("$.pinnedAt").value(pinnedAt));

        // GET /pinned (the literal segment) — the pin list carries the same fields
        mockMvc.perform(get("/api/channels/" + channelId + "/messages/pinned")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(messageId))
                .andExpect(jsonPath("$[0].pinned").value(true))
                .andExpect(jsonPath("$[0].pinnedBy").value("alice"));

        // unpin (idempotent) -> the list empties; the row stays in history
        mockMvc.perform(delete(pinUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(false))
                .andExpect(jsonPath("$.pinnedBy").doesNotExist())
                .andExpect(jsonPath("$.pinnedAt").doesNotExist());
        mockMvc.perform(delete(pinUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(false));
        mockMvc.perform(get("/api/channels/" + channelId + "/messages/pinned")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void pinnedList_ordersOldestPinFirst() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long firstId = postMessage(ownerToken, channelId, "first pinned");
        Long secondId = postMessage(ownerToken, channelId, "second pinned");

        mockMvc.perform(put(pinUrl(channelId, secondId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        Thread.sleep(5); // pinnedAt differs between the two pins
        mockMvc.perform(put(pinUrl(channelId, firstId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // ASC by pinnedAt even though the SECOND message was pinned first — clients render the
        // LAST element as the latest pin
        mockMvc.perform(get("/api/channels/" + channelId + "/messages/pinned")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(secondId))
                .andExpect(jsonPath("$[1].id").value(firstId));
    }

    @Test
    void pinGate_manageMessagesInServer_anyParticipantInDm() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "alice's words");
        String bobToken = login("bob", "supersecret");

        // plain member -> 403 (pin needs MANAGE_MESSAGES, not just membership)
        mockMvc.perform(put(pinUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // granted MANAGE_MESSAGES -> 200, the pinner is recorded
        Long roleId = createRole(ownerToken, "Moderator", "MANAGE_MESSAGES");
        assignRoles(ownerToken, bobId, roleId);
        mockMvc.perform(put(pinUrl(channelId, messageId))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinnedBy").value("bob"));

        // in a DM any participant pins/unpins (the Discord rule; the DM branch of hasInChannel
        // turns the MANAGE_MESSAGES ask into a participant check)
        String carolToken = registerAndLogin("carol", "supersecret");
        Long dmId = createDm(ownerToken, bobId);
        Long dmMessageId = postMessage(ownerToken, dmId, "dm words");
        mockMvc.perform(put(pinUrl(dmId, dmMessageId))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinnedBy").value("bob"));

        // a NON-participant third party gets nothing from the DM — not even the pin list
        mockMvc.perform(get("/api/channels/" + dmId + "/messages/pinned")
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void pinCap_fiftyPerChannel_51stIsBadRequest() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        for (int messageIndex = 0; messageIndex < 50; messageIndex++) {
            Long messageId = postMessage(ownerToken, channelId, "msg " + messageIndex);
            mockMvc.perform(put(pinUrl(channelId, messageId))
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isOk());
        }
        Long extraId = postMessage(ownerToken, channelId, "the 51st");
        mockMvc.perform(put(pinUrl(channelId, extraId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());

        // the cap is per channel — another channel's pin is unaffected
        Long otherChannelId = createChannel(ownerToken, "random", "TEXT");
        Long otherMessageId = postMessage(ownerToken, otherChannelId, "elsewhere");
        mockMvc.perform(put(pinUrl(otherChannelId, otherMessageId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // re-pinning one of the 50 does not trip the cap (idempotent no-op path)
        // — covered by the round-trip test; here just assert the list still holds 50
        mockMvc.perform(get("/api/channels/" + channelId + "/messages/pinned")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50));
    }

    @Test
    void pin_lookupGuards() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");
        Long messageId = postMessage(ownerToken, channelId, "hello");

        // unknown message / unknown channel -> 404
        mockMvc.perform(put(pinUrl(channelId, 999999L))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(pinUrl(999999L, messageId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        // a message id from ANOTHER channel -> 404 (no cross-channel reach)
        Long otherChannelId = createChannel(ownerToken, "random", "TEXT");
        mockMvc.perform(put(pinUrl(otherChannelId, messageId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        // deleting the pinned message drops the pin with it (hard delete — the pin dies honestly)
        mockMvc.perform(delete("/api/channels/" + channelId + "/messages/" + messageId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/channels/" + channelId + "/messages/pinned")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- helpers ---

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

    private static String pinUrl(Long channelId, Long messageId) {
        return "/api/channels/" + channelId + "/messages/" + messageId + "/pin";
    }
}
