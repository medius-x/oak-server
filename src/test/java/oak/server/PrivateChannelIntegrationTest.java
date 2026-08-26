package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.Channel;
import oak.server.entity.ChannelRoleOverride;
import oak.server.entity.ChannelUserOverride;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.ChannelUserOverrideRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.service.RoleSeeder;
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

    private String registerAndLogin(String username, String password) throws Exception {
        register(username, password);
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
