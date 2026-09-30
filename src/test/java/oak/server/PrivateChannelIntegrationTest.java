package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.entity.Channel;
import oak.server.entity.ChannelRoleOverride;
import oak.server.entity.ChannelUserOverride;
import oak.server.entity.Permission;
import oak.server.entity.Permissions;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.ChannelUserOverrideRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.service.RoleSeeder;
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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Private channels end-to-end on the H2 test profile: the {@code VIEW_CHANNEL} permission, the
 * layered override resolution (a role/member allow must beat an {@code @everyone} deny), the
 * viewer-filtered {@code GET /api/channels} (hidden channels omitted, locked ones flagged), the
 * VIEW master gate (a hidden channel denies every other permission without an explicit deny), and
 * the member-override REST round-trip. The WS SUBSCRIBE gate
 * ({@code ChannelSubscriptionInterceptor}) is NOT exercisable via MockMvc — manual smoke.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PrivateChannelIntegrationTest {

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
    @Autowired
    private ChannelUserOverrideRepository channelUserOverrideRepository;
    @Autowired
    private RoleSeeder roleSeeder;
    @Autowired
    private VoiceMembershipTracker voiceTracker;

    /** Sessions staged into the voice registry by {@link #stageInVoice} — the tracker is a singleton
     *  of the cached Spring context and NOT transactional, so without this cleanup a keeper's
     *  session would leak into every later test in the class. */
    private final List<String> stagedVoiceSessions = new ArrayList<>();

    @AfterEach
    void clearStagedVoiceSessions() {
        stagedVoiceSessions.forEach(voiceTracker::exit);
        stagedVoiceSessions.clear();
    }

    /** A database seeded before the VIEW_CHANNEL bit existed would turn every channel invisible for
     *  every non-owner — the boot-time re-grant in {@link RoleSeeder} is the repair. Reproduce the
     *  broken state, prove the darkness, then repair and prove the light. */
    @Test
    void everyoneMigrationRestoresViewBitAndVisibility() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");

        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        everyone.setPermissions(everyone.getPermissions() & ~Permission.VIEW_CHANNEL.bit);
        roleRepository.saveAndFlush(everyone);

        // The trap, live: with the base VIEW bit off, bob sees nothing.
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + channelId + ")]").isEmpty());

        roleSeeder.ensureEveryoneViewChannel();

        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + channelId + ")]").isNotEmpty());
    }

    /** Hidden channel: the outsider's list omits it; a MEMBER allow and a ROLE allow both beat the
     *  @everyone VIEW deny (the layered-resolution core); the owner's ADMINISTRATOR bypass sees it. */
    @Test
    void hiddenChannelOmittedForOutsiderVisibleForMemberRoleAndOwner() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String outsiderToken = registerAndLogin("bob", "supersecret");
        register("carol", "supersecret");
        register("dave", "supersecret");
        Long channelId = createChannel(ownerToken, "secret", "TEXT");

        Channel channel = channelRepository.findById(channelId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, everyone, 0L, Permission.VIEW_CHANNEL.bit));

        // carol: a personal allow — the "invite these specific people" case.
        User carol = userRepository.findByUsername("carol").orElseThrow();
        channelUserOverrideRepository.saveAndFlush(
                new ChannelUserOverride(channel, carol, Permission.VIEW_CHANNEL.bit, 0L));

        // dave: a role allow.
        Long roleId = createRole(ownerToken, "InnerCircle", "VIEW_CHANNEL");
        User dave = userRepository.findByUsername("dave").orElseThrow();
        mockMvc.perform(put("/api/members/" + dave.getId() + "/roles")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"roleIds\":[" + roleId + "]}"))
                .andExpect(status().isOk());
        Role innerCircle = roleRepository.findByName("InnerCircle").orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, innerCircle, Permission.VIEW_CHANNEL.bit, 0L));

        String carolToken = login("carol", "supersecret");
        String daveToken = login("dave", "supersecret");

        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + channelId + ")]").isEmpty());
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + channelId + ")]").isNotEmpty());
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + daveToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + channelId + ")]").isNotEmpty());
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + channelId + ")]").isNotEmpty());
    }

    /** The per-viewer {@code locked} flag: visible but unusable (no history read for TEXT, no
     *  connect for VOICE) — and a member allow clears it for the invited user. */
    @Test
    void lockedFlagReflectsUsabilityPerViewer() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        register("carol", "supersecret");
        Long textId = createChannel(ownerToken, "readonly", "TEXT");
        Long voiceId = createChannel(ownerToken, "lounge", "VOICE");

        Channel text = channelRepository.findById(textId).orElseThrow();
        Channel voice = channelRepository.findById(voiceId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(text, everyone, 0L, Permission.READ_MESSAGE_HISTORY.bit));
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(voice, everyone, 0L, Permission.CONNECT.bit));

        // carol is invited into the text channel with read back.
        User carol = userRepository.findByUsername("carol").orElseThrow();
        channelUserOverrideRepository.saveAndFlush(
                new ChannelUserOverride(text, carol, Permission.READ_MESSAGE_HISTORY.bit, 0L));
        String carolToken = login("carol", "supersecret");

        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + textId + ")].locked").value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.id==" + voiceId + ")].locked").value(org.hamcrest.Matchers.hasItem(true)));
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + textId + ")].locked").value(org.hamcrest.Matchers.hasItem(false)));
    }

    /** The VIEW master gate: a hidden channel denies EVERYTHING for the outsider — reading,
     *  posting, even the read-state snapshot — with no explicit READ/SEND deny anywhere (the
     *  @everyone base still grants both; only VIEW is denied). */
    @Test
    void hiddenChannelMasterGateBlocksAllEndpoints() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "vault", "TEXT");

        Channel channel = channelRepository.findById(channelId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, everyone, 0L, Permission.VIEW_CHANNEL.bit));

        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"content\":\"let me in\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/read-states").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.channelId==" + channelId + ")]").isEmpty());
    }

    /** Regression for the layered resolution: under the old accumulate-then-deny-wins scheme a role
     *  allow could NEVER beat an @everyone deny — which made private channels impossible. */
    @Test
    void roleAllowBeatsEveryoneDenyInLayeredResolution() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "announcements", "TEXT");

        Channel channel = channelRepository.findById(channelId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, everyone, 0L, Permission.SEND_MESSAGES.bit));
        Long roleId = createRole(ownerToken, "Writers", "SEND_MESSAGES");
        User bob = userRepository.findByUsername("bob").orElseThrow();
        mockMvc.perform(put("/api/members/" + bob.getId() + "/roles")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"roleIds\":[" + roleId + "]}"))
                .andExpect(status().isOk());
        Role writers = roleRepository.findByName("Writers").orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, writers, Permission.SEND_MESSAGES.bit, 0L));

        // bob carries Writers; its channel allow overrides the @everyone deny.
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"content\":\"invited writer\"}"))
                .andExpect(status().isCreated());
    }

    /** The member-override REST surface: names (not masks) on the wire, username joined in, CRUD
     *  round-trip, MANAGE_CHANNELS enforced, and the row actually resolving for the target user. */
    @Test
    void userOverrideCrudRoundTripsViaRest() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        register("carol", "supersecret");
        Long channelId = createChannel(ownerToken, "priv", "TEXT");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();

        // A non-manager cannot write member overrides.
        mockMvc.perform(put("/api/channels/" + channelId + "/overrides/users/" + bobId)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"allow\":[\"VIEW_CHANNEL\"],\"deny\":[]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/channels/" + channelId + "/overrides/users/" + bobId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"allow\":[\"VIEW_CHANNEL\"],\"deny\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(bobId))
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.allow").value(org.hamcrest.Matchers.hasItem("VIEW_CHANNEL")));

        mockMvc.perform(get("/api/channels/" + channelId + "/overrides/users")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("bob"));

        // The row resolves: even with VIEW denied for @everyone, bob still sees the channel.
        Channel channel = channelRepository.findById(channelId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, everyone, 0L, Permission.VIEW_CHANNEL.bit));
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + channelId + ")]").isNotEmpty());

        mockMvc.perform(delete("/api/channels/" + channelId + "/overrides/users/" + bobId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/channels/" + channelId + "/overrides/users")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // --- helpers (the RbacIntegrationTest idiom) ---

    /** Live-session enforcement: rewriting an override evicts a member who just lost CONNECT from
     *  the room they are ALREADY sitting in (registry exit + forced layers + KICKED push — the
     *  noop-Janus test profile keeps the forced layers silent), while a member whose layer-3 user
     *  allow still grants CONNECT stays put. */
    @Test
    void overrideMutationEvictsLiveSessionsWithoutConnect() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        register("carol", "supersecret");
        Long channelId = createChannel(ownerToken, "vip-lounge", "VOICE");

        Channel channel = channelRepository.findById(channelId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, everyone, 0L, Permission.CONNECT.bit));
        stageInVoice("bob", channelId);
        stageInVoice("carol", channelId);

        // carol gets a personal allow (layer 3 beats the @everyone deny); bob gets nothing — the
        // mutation itself is the trigger, no separate REST call per member.
        Long carolId = userRepository.findByUsername("carol").orElseThrow().getId();
        mockMvc.perform(put("/api/channels/" + channelId + "/overrides/users/" + carolId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"allow\":[\"CONNECT\"],\"deny\":[]}"))
                .andExpect(status().isOk());

        assertTrue(voiceSnapshotUsernames(channelId).contains("carol"), "carol keeps her allow — stays");
        assertFalse(voiceSnapshotUsernames(channelId).contains("bob"), "bob lost CONNECT — evicted live");
    }

    /** Deleting a channel force-evicts everyone sitting in its voice room — the channels.deleted
     *  broadcast only honest clients act on, the eviction also covers the rest. */
    @Test
    void channelDeletionEvictsEveryoneInVoice() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "temp", "VOICE");
        stageInVoice("bob", channelId);

        mockMvc.perform(delete("/api/channels/" + channelId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        assertFalse(voiceSnapshotUsernames(channelId).contains("bob"), "the room is gone — bob is evicted");
    }

    /** The role-mask hook: editing a role's permissions (here @everyone's base CONNECT) sweeps
     *  every occupied room; a cosmetic-only edit of the same role must not. */
    @Test
    void rolePermissionEditEvictsLiveVoiceSessions() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "lounge", "VOICE");
        stageInVoice("bob", channelId);
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        Long everyoneId = everyone.getId();

        // Cosmetic-only edit (mentionable): the mask is untouched — nobody is evicted.
        mockMvc.perform(patch("/api/roles/" + everyoneId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"mentionable\":true}"))
                .andExpect(status().isOk());
        assertTrue(voiceSnapshotUsernames(channelId).contains("bob"), "mask untouched — bob stays");

        // Strip CONNECT from @everyone: bob loses it in every room (OWNER alice bypasses).
        long withoutConnect = roleRepository.findById(everyoneId).orElseThrow().getPermissions()
                & ~Permission.CONNECT.bit;
        String permissionsJson = Permissions.names(withoutConnect).stream()
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(","));
        mockMvc.perform(patch("/api/roles/" + everyoneId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"permissions\":[" + permissionsJson + "]}"))
                .andExpect(status().isOk());

        assertFalse(voiceSnapshotUsernames(channelId).contains("bob"), "CONNECT stripped — bob evicted");
    }

    /** The member-roles hook: replacing one member's roles rechecks only THAT member — the one
     *  stripped of the CONNECT-granting role is evicted, the still-holding member keeps talking. */
    @Test
    void memberRoleRemovalEvictsOnlyThatMember() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        register("carol", "supersecret");
        Long channelId = createChannel(ownerToken, "vip-lounge", "VOICE");

        // Private: @everyone denied CONNECT; a "Vip" role with a channel allow carries it.
        Channel channel = channelRepository.findById(channelId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, everyone, 0L, Permission.CONNECT.bit));
        Long roleId = createRole(ownerToken, "Vip", "CONNECT");
        Role vip = roleRepository.findByName("Vip").orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, vip, Permission.CONNECT.bit, 0L));
        User bob = userRepository.findByUsername("bob").orElseThrow();
        User carol = userRepository.findByUsername("carol").orElseThrow();
        mockMvc.perform(put("/api/members/" + bob.getId() + "/roles")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"roleIds\":[" + roleId + "]}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/members/" + carol.getId() + "/roles")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"roleIds\":[" + roleId + "]}"))
                .andExpect(status().isOk());
        stageInVoice("bob", channelId);
        stageInVoice("carol", channelId);
        assertTrue(voiceSnapshotUsernames(channelId).contains("bob"));
        assertTrue(voiceSnapshotUsernames(channelId).contains("carol"));

        // Strip carol's roles: she falls back to the @everyone deny — evicted; bob keeps Vip.
        mockMvc.perform(put("/api/members/" + carol.getId() + "/roles")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"roleIds\":[]}"))
                .andExpect(status().isOk());

        assertFalse(voiceSnapshotUsernames(channelId).contains("carol"), "carol lost Vip — evicted");
        assertTrue(voiceSnapshotUsernames(channelId).contains("bob"), "bob still holds Vip — stays");
    }

    /** Stages a user into the live-voice registry the way a STOMP {@code /app/voice.join} would —
     *  the registry is the enforcement's input, so tests drive it directly (MockMvc cannot SEND). */
    private void stageInVoice(String username, Long channelId) {
        String sessionId = "test-session-" + username;
        voiceTracker.enter(sessionId, username, channelId);
        stagedVoiceSessions.add(sessionId);
    }

    private List<String> voiceSnapshotUsernames(Long channelId) {
        return voiceTracker.snapshot().stream()
                .filter(room -> channelId.equals(room.channelId()))
                .findFirst()
                .map(VoiceChannelMembersDto::usernames)
                .orElse(List.of());
    }

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
        MvcResult result = mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"type\":\"%s\"}", name, type)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long createRole(String token, String name, String permission) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"permissions\":[\"%s\"]}", name, permission)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
