package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.websocket.VoiceMembershipTracker;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The AFK settings endpoint: open to read for any member, writable only under
 * MANAGE_CHANNELS, a non-VOICE channel is rejected, an unknown id is a 404, and a null
 * channel disables the sweep. Mirrors {@code ChannelCategoryIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AfkSettingsIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestInvites testInvites;

    /** The in-memory voice registry is a singleton across the cached context — every
     *  staged session MUST leave, or it leaks into the next test (the stageInVoice lesson). */
    @Autowired
    private VoiceMembershipTracker tracker;

    @AfterEach
    void leaveStagedVoice() {
        tracker.snapshot().forEach(room -> new java.util.HashSet<>(room.usernames())
                .forEach(username -> tracker.kick(room.channelId(), username)));
    }

    @Test
    void roundTripsTheSettingsUnderManageChannels() throws Exception {
        String ownerToken = registerAndLogin("afalice", "supersecret");
        String plebToken = registerAndLogin("afbob", "supersecret");
        Long voiceId = createChannel(ownerToken, "afk-room", "VOICE");

        // anyone authenticated may READ (the sweep's settings are not a secret)
        mockMvc.perform(get("/api/server/afk").header("Authorization", "Bearer " + plebToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afkChannelId").doesNotExist());

        // a plain member cannot write
        mockMvc.perform(put("/api/server/afk").header("Authorization", "Bearer " + plebToken)
                        .contentType(JSON)
                        .content(String.format("{\"afkChannelId\":%d,\"afkTimeoutMinutes\":15}", voiceId)))
                .andExpect(status().isForbidden());

        // the owner configures the sweep
        mockMvc.perform(put("/api/server/afk").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"afkChannelId\":%d,\"afkTimeoutMinutes\":15}", voiceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afkChannelId").value(voiceId))
                .andExpect(jsonPath("$.afkTimeoutMinutes").value(15));

        mockMvc.perform(get("/api/server/afk").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afkChannelId").value(voiceId));

        // the channel list badges the room (the ChannelResponse AFK flag)
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + plebToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(String.format("$[?(@.id==%d)].afk", voiceId),
                        org.hamcrest.Matchers.hasItem(true)));

        // a null channel disables the feature
        mockMvc.perform(put("/api/server/afk").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"afkChannelId\":null,\"afkTimeoutMinutes\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afkChannelId").doesNotExist());
    }

    @Test
    void validatesTheTargetAndTheTimeout() throws Exception {
        String ownerToken = registerAndLogin("afcarol", "supersecret");
        Long textId = createChannel(ownerToken, "afk-text", "TEXT");

        mockMvc.perform(put("/api/server/afk").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"afkChannelId\":%d,\"afkTimeoutMinutes\":15}", textId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/server/afk").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"afkChannelId\":999999,\"afkTimeoutMinutes\":15}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/server/afk").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"afkChannelId\":null,\"afkTimeoutMinutes\":4}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/server/afk").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"afkChannelId\":null,\"afkTimeoutMinutes\":10081}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removingTheDesignationLiftsTheChannelsModeratorMutes() throws Exception {
        String ownerToken = registerAndLogin("afdave", "supersecret");
        Long voiceId = createChannel(ownerToken, "afk-stuck", "VOICE");
        mockMvc.perform(put("/api/server/afk").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"afkChannelId\":%d,\"afkTimeoutMinutes\":15}", voiceId)))
                .andExpect(status().isOk());

        // a member entered while the room was the AFK channel — the policy muted them
        tracker.enter("afk-test-session", "afkarin", voiceId);
        tracker.setMuted(voiceId, "afkarin", true);

        // the designation is removed (Disabled) — the channel-wide suppression dies with it
        mockMvc.perform(put("/api/server/afk").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content("{\"afkChannelId\":null,\"afkTimeoutMinutes\":15}"))
                .andExpect(status().isOk());

        assertFalse(tracker.isMuted(voiceId, "afkarin"),
                "the AFK policy's moderator mute is lifted with the designation");
    }

    @Test
    void designatingAnOccupiedRoomMutesTheSittingMembersRetroactively() throws Exception {
        String ownerToken = registerAndLogin("afellen", "supersecret");
        Long voiceId = createChannel(ownerToken, "afk-busy", "VOICE");

        // a member sits in the room BEFORE it becomes the AFK channel
        tracker.enter("afk-busy-session", "affrank", voiceId);
        assertFalse(tracker.isMuted(voiceId, "affrank"));

        mockMvc.perform(put("/api/server/afk").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON)
                        .content(String.format("{\"afkChannelId\":%d,\"afkTimeoutMinutes\":15}", voiceId)))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertTrue(tracker.isMuted(voiceId, "affrank"),
                "the designation landing on an occupied room suppresses whoever is already sitting there");
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(testInvites.registrationBody(username, password)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }

    private Long createChannel(String token, String name, String type) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"type\":\"%s\"}", name, type)))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
