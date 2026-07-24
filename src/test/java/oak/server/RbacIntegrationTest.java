package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.Channel;
import oak.server.entity.ChannelRoleOverride;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end RBAC through MockMvc on the in-memory H2 test profile. The {@link oak.server.service.RoleSeeder}
 * has already committed {@code @everyone} + {@code OWNER} at startup; each test registers a first member
 * (→ OWNER) and a second (→ {@code @everyone} only) and exercises permission checks, role assignment,
 * channel overrides, hierarchy and banning.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RbacIntegrationTest {

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

    @Test
    void firstRegisteredUserIsOwnerAndHasAdministrator() throws Exception {
        String token = registerAndLogin("alice", "supersecret");

        mockMvc.perform(get("/api/users/me/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(true))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("ADMINISTRATOR")))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("MANAGE_CHANNELS")));
    }

    @Test
    void nonOwnerWithoutManageChannelsCannotCreateChannel() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret"); // OWNER
        String bobToken = registerAndLogin("bob", "supersecret"); // @everyone only

        // bob has no MANAGE_CHANNELS -> 403
        mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content(channelBody("bob-channel", "TEXT")))
                .andExpect(status().isForbidden());

        // owner can (sanity: the gate grants, not only denies)
        mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content(channelBody("owner-channel", "TEXT")))
                .andExpect(status().isCreated());
    }

    @Test
    void roleWithManageChannelsLetsMemberCreateChannel() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();

        Long roleId = createRole(ownerToken, "Channel Admin", "MANAGE_CHANNELS");
        assignRoles(ownerToken, bobId, roleId);

        String bobToken = login("bob", "supersecret");
        // bob now carries MANAGE_CHANNELS via the custom role -> 201 (not via ADMINISTRATOR bypass)
        mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content(channelBody("bob-made", "TEXT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("bob-made"));
    }

    @Test
    void channelOverrideDenyBlocksSendForEveryone() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "secret", "TEXT");

        // bob can post by default (@everyone grants SEND_MESSAGES)
        mockMvc.perform(post("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().isCreated());

        // deny SEND_MESSAGES for @everyone on this channel (seeded directly via the repository)
        Channel channel = channelRepository.findById(channelId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, everyone, 0L, Permission.SEND_MESSAGES.bit));

        // bob is now blocked by the override
        mockMvc.perform(post("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"content\":\"again\"}"))
                .andExpect(status().isForbidden());

        // owner bypasses overrides (ADMINISTRATOR) and can still post
        mockMvc.perform(post("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"content\":\"owner speaks\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void hierarchy_ownerImmune_memberCannotKickOwner() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long ownerId = userRepository.findByUsername("alice").orElseThrow().getId();

        Long roleId = createRole(ownerToken, "Mod", "KICK_MEMBERS");
        assignRoles(ownerToken, bobId, roleId);

        String bobToken = login("bob", "supersecret");
        // bob has KICK_MEMBERS but cannot act on the OWNER (hierarchy) -> 403
        mockMvc.perform(post("/api/members/" + ownerId + "/kick").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // owner can kick bob -> 200
        mockMvc.perform(post("/api/members/" + bobId + "/kick").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    void ban_blocksLogin_unban_restores() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();

        // owner bans bob
        mockMvc.perform(post("/api/members/" + bobId + "/ban").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // bob can no longer log in -> 403
        mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody("bob", "supersecret")))
                .andExpect(status().isForbidden());

        // owner unbans; bob can log in again
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/members/" + bobId + "/ban").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody("bob", "supersecret")))
                .andExpect(status().isOk());
    }

    // --- helpers ---

    private String registerAndLogin(String username, String password) throws Exception {
        register(username, password);
        return login(username, password);
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(regBody(username, password)))
                .andExpect(status().isCreated());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private Long createChannel(String token, String name, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(channelBody(name, type)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Long createRole(String token, String name, String permission) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(roleBody(name, permission)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void assignRoles(String token, Long userId, Long roleId) throws Exception {
        mockMvc.perform(put("/api/members/" + userId + "/roles").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"roleIds\":[" + roleId + "]}"))
                .andExpect(status().isOk());
    }

    private static String regBody(String username, String password) {
        return String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
    }

    private static String loginBody(String username, String password) {
        return String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
    }

    private static String channelBody(String name, String type) {
        return String.format("{\"name\":\"%s\",\"type\":\"%s\"}", name, type);
    }

    private static String roleBody(String name, String permission) {
        return String.format("{\"name\":\"%s\",\"permissions\":[\"%s\"]}", name, permission);
    }
}
