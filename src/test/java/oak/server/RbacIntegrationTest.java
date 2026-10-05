package oak.server;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void firstRegisteredUserIsOwnerAndHasAdministrator() throws Exception {
        String token = registerAndLogin("alice", "supersecret");

        mockMvc.perform(get("/api/users/me/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(true))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("ADMINISTRATOR")))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("MANAGE_CHANNELS")));
    }

    @Test
    void retiredPermissionBitsNeverSurfaceOnTheWire() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");

        // The catalog carries no reserved rows (they only occupy their ordinal)…
        MvcResult catalogResult = mockMvc.perform(get("/api/permissions").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        List<String> catalogNames = JsonPath.read(catalogResult.getResponse().getContentAsString(), "$[*].name");
        assertFalse(catalogNames.stream().anyMatch(name -> name.startsWith("RESERVED_")), "catalog must carry no reserved bits");
        assertFalse(catalogNames.contains("EMBED_LINKS"));
        assertFalse(catalogNames.contains("USE_EXTERNAL_EMOJIS"));

        // …and the OWNER's "every permission" name list skips them too — the ADMINISTRATOR
        // short-circuit sets every BIT (reserved included), names() must filter them out.
        mockMvc.perform(get("/api/users/me/permissions").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("RESERVED_EMBED_LINKS"))))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("RESERVED_USE_EXTERNAL_EMOJIS"))));

        // Fresh @everyone defaults carry neither retired bit (V16 clears legacy rows).
        long retiredBits = Permission.RESERVED_EMBED_LINKS.bit | Permission.RESERVED_USE_EXTERNAL_EMOJIS.bit;
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        assertEquals(0L, everyone.getPermissions() & retiredBits);
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

        Long roleId = createRole(ownerToken, "ChannelAdmin", "MANAGE_CHANNELS");
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
    void hierarchy_ownerImmune_memberCannotRemoveOwner() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long ownerId = userRepository.findByUsername("alice").orElseThrow().getId();

        Long roleId = createRole(ownerToken, "Mod", "KICK_MEMBERS");
        assignRoles(ownerToken, bobId, roleId);

        String bobToken = login("bob", "supersecret");
        // bob has KICK_MEMBERS but cannot act on the OWNER (hierarchy) -> 403
        mockMvc.perform(post("/api/members/" + ownerId + "/remove").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // owner can remove bob -> 200
        mockMvc.perform(post("/api/members/" + bobId + "/remove").header("Authorization", "Bearer " + ownerToken))
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

        // the management roster marks bob as banned
        mockMvc.perform(get("/api/members").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId==" + bobId + ")].banned",
                        org.hamcrest.Matchers.hasItem(true)));

        // bob can no longer log in -> 403
        mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody("bob", "supersecret")))
                .andExpect(status().isForbidden());

        // owner unbans; bob can log in again
        mockMvc.perform(delete("/api/members/" + bobId + "/ban").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/members").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId==" + bobId + ")].banned",
                        org.hamcrest.Matchers.hasItem(false)));
        mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(loginBody("bob", "supersecret")))
                .andExpect(status().isOk());
    }

    @Test
    void bannedMemberTokenRejectedOnRest() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret"); // valid access JWT issued BEFORE the ban
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();

        // owner bans bob
        mockMvc.perform(post("/api/members/" + bobId + "/ban").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // bob's still-valid access JWT is now rejected on every REST endpoint — ban takes effect at
        // once (not only on the next login/CONNECT), via the JwtAuthenticationFilter banned check.
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void channelOverrideCrud_roundTripsViaRest() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "override-room", "TEXT");
        Long everyoneId = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow().getId();

        // no overrides initially
        mockMvc.perform(get("/api/channels/" + channelId + "/overrides").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // PUT upserts (deny SEND_MESSAGES, allow MANAGE_MESSAGES for @everyone)
        mockMvc.perform(put("/api/channels/" + channelId + "/overrides/" + everyoneId)
                        .header("Authorization", "Bearer " + ownerToken).contentType(JSON)
                        .content("{\"allow\":[\"MANAGE_MESSAGES\"],\"deny\":[\"SEND_MESSAGES\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleId").value(everyoneId))
                .andExpect(jsonPath("$.deny[0]").value("SEND_MESSAGES"));

        // GET list now has one
        mockMvc.perform(get("/api/channels/" + channelId + "/overrides").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // a non-manager (bob, @everyone only) cannot edit overrides -> 403
        String bobToken = registerAndLogin("bob", "supersecret");
        mockMvc.perform(put("/api/channels/" + channelId + "/overrides/" + everyoneId)
                        .header("Authorization", "Bearer " + bobToken).contentType(JSON)
                        .content("{\"allow\":[],\"deny\":[]}"))
                .andExpect(status().isForbidden());

        // DELETE removes it (as owner)
        mockMvc.perform(delete("/api/channels/" + channelId + "/overrides/" + everyoneId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/channels/" + channelId + "/overrides").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void attachFiles_isChannelScoped() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "media", "TEXT");
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();

        // @everyone grants ATTACH_FILES by default -> bob can post WITH an attachment
        mockMvc.perform(post("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON)
                        .content("{\"content\":\"pic\",\"attachments\":[{\"key\":\"attachments/x/y.png\",\"fileName\":\"y.png\"}]}"))
                .andExpect(status().isCreated());

        // deny ATTACH_FILES for @everyone on this channel
        channelRoleOverrideRepository.saveAndFlush(new ChannelRoleOverride(
                channelRepository.findById(channelId).orElseThrow(), everyone, 0L, Permission.ATTACH_FILES.bit));

        // bob can no longer post with an attachment -> 403 ...
        mockMvc.perform(post("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON)
                        .content("{\"content\":\"pic2\",\"attachments\":[{\"key\":\"attachments/x/z.png\",\"fileName\":\"z.png\"}]}"))
                .andExpect(status().isForbidden());

        // ... but text-only still works (SEND_MESSAGES still granted)
        mockMvc.perform(post("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"content\":\"just text\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void connect_gatesVoiceRoomEndpoint() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long voiceId = createChannel(ownerToken, "lounge", "VOICE");
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();

        // @everyone grants CONNECT by default -> bob gets room info
        mockMvc.perform(get("/api/channels/" + voiceId + "/room").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        // deny CONNECT for @everyone
        channelRoleOverrideRepository.saveAndFlush(new ChannelRoleOverride(
                channelRepository.findById(voiceId).orElseThrow(), everyone, 0L, Permission.CONNECT.bit));

        // bob now denied -> 403 ...
        mockMvc.perform(get("/api/channels/" + voiceId + "/room").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // ... owner bypasses (ADMINISTRATOR) -> 200
        mockMvc.perform(get("/api/channels/" + voiceId + "/room").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    void channelMyPermissions_reflectsSendOverride() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "room", "TEXT");

        // bob has SEND_MESSAGES + ATTACH_FILES via @everyone by default
        mockMvc.perform(get("/api/channels/" + channelId + "/permissions/me").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sendMessages").value(true))
                .andExpect(jsonPath("$.attachFiles").value(true))
                .andExpect(jsonPath("$.stream").value(true));

        // deny SEND_MESSAGES for @everyone on this channel
        Channel channel = channelRepository.findById(channelId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, everyone, 0L, Permission.SEND_MESSAGES.bit));

        // bob can no longer send here → sendMessages=false (attach still true). This is what the client
        // uses to hide the composer, so it must reflect the per-channel override, not just @everyone.
        mockMvc.perform(get("/api/channels/" + channelId + "/permissions/me").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sendMessages").value(false))
                .andExpect(jsonPath("$.attachFiles").value(true))
                .andExpect(jsonPath("$.stream").value(true));
    }

    @Test
    void channelMyPermissions_streamDenyHidesTheWireFlag() throws Exception {
        // Security round 6: the share/camera gate is client-side, fed by this flag — a per-channel
        // STREAM deny must reach the wire (the AFK-style hiding is the client's part).
        String ownerToken = registerAndLogin("streamer", "supersecret");
        String bobToken = registerAndLogin("watcher", "supersecret");
        Long voiceId = createChannel(ownerToken, "stage", "VOICE");

        mockMvc.perform(get("/api/channels/" + voiceId + "/permissions/me").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stream").value(true));

        Channel channel = channelRepository.findById(voiceId).orElseThrow();
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        channelRoleOverrideRepository.saveAndFlush(
                new ChannelRoleOverride(channel, everyone, 0L, Permission.STREAM.bit));

        mockMvc.perform(get("/api/channels/" + voiceId + "/permissions/me").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stream").value(false));
        // the owner bypasses the override through ADMINISTRATOR
        mockMvc.perform(get("/api/channels/" + voiceId + "/permissions/me").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stream").value(true));
    }

    @Test
    void deleteRole_clearsItsOverridesAndAssignments() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(ownerToken, "ov-room", "TEXT");
        Long roleId = createRole(ownerToken, "Doomed", "MANAGE_MESSAGES");

        // give the role a channel override (this is what used to make delete fail with an FK violation)
        mockMvc.perform(put("/api/channels/" + channelId + "/overrides/" + roleId)
                        .header("Authorization", "Bearer " + ownerToken).contentType(JSON)
                        .content("{\"allow\":[],\"deny\":[\"SEND_MESSAGES\"]}"))
                .andExpect(status().isOk());

        // deleting the role must clear its override + assignments, not 500
        mockMvc.perform(delete("/api/roles/" + roleId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        // the override is gone too
        mockMvc.perform(get("/api/channels/" + channelId + "/overrides").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void ownerRoleCannotBeAssignedOnlyKeptForHolder() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        register("bob", "supersecret");
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();
        Long ownerId = userRepository.findByUsername("alice").orElseThrow().getId();
        Long ownerRoleId = roleRepository.findByKind(RoleKind.OWNER).orElseThrow().getId();

        // assigning OWNER to a non-owner (bob) is rejected — OWNER is transfer-only, not assignable
        mockMvc.perform(put("/api/members/" + bobId + "/roles").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"roleIds\":[" + ownerRoleId + "]}"))
                .andExpect(status().isForbidden());

        // but re-assigning the owner's own roles (the request carries their OWNER id) succeeds — the
        // OWNER id is treated as a keep, not an assign
        Long extraRoleId = createRole(ownerToken, "Extra", "MANAGE_MESSAGES");
        mockMvc.perform(put("/api/members/" + ownerId + "/roles").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"roleIds\":[" + ownerRoleId + "," + extraRoleId + "]}"))
                .andExpect(status().isOk());

        // the owner still holds OWNER (the re-assign kept it)
        mockMvc.perform(get("/api/users/me/permissions").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(true));
    }

    @Test
    void deleteChannel_requiresManageChannelsAndClearsDependents() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        registerAndLogin("bob", "supersecret"); // @everyone only
        String bobToken = login("bob", "supersecret");

        Long channelId = createChannel(ownerToken, "doomed", "TEXT");
        Long everyoneId = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow().getId();
        // A message and an override both depend on the channel — both must be cleared before the channel
        // is deleted, or the channel DELETE is rejected by the FK (no ON DELETE CASCADE under ddl-auto=update).
        mockMvc.perform(post("/api/channels/" + channelId + "/messages").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"content\":\"hello\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/channels/" + channelId + "/overrides/" + everyoneId)
                        .header("Authorization", "Bearer " + ownerToken).contentType(JSON)
                        .content("{\"allow\":[],\"deny\":[\"SEND_MESSAGES\"]}"))
                .andExpect(status().isOk());

        // bob (no MANAGE_CHANNELS) -> 403; the channel is left untouched
        mockMvc.perform(delete("/api/channels/" + channelId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        assertTrue(channelRepository.existsById(channelId));

        // owner deletes it -> 204, and the channel + its dependents are gone
        mockMvc.perform(delete("/api/channels/" + channelId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        assertFalse(channelRepository.existsById(channelId));
        assertTrue(channelRoleOverrideRepository.findByChannelId(channelId).isEmpty());

        // deleting the now-non-existent channel -> 404
        mockMvc.perform(delete("/api/channels/" + channelId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void renameChannel_requiresManageChannels_andValidatesTheName() throws Exception {
        String ownerToken = registerAndLogin("alice", "supersecret");
        registerAndLogin("bob", "supersecret"); // @everyone only
        String bobToken = login("bob", "supersecret");
        Long channelId = createChannel(ownerToken, "general", "TEXT");

        // bob (no MANAGE_CHANNELS) -> 403, name untouched
        mockMvc.perform(patch("/api/channels/" + channelId + "/name")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"name\":\"hacked\"}"))
                .andExpect(status().isForbidden());
        assertEquals("general", channelRepository.findById(channelId).orElseThrow().getName());

        // owner renames -> the response carries the fresh name (and the actor's own locked flag)
        mockMvc.perform(patch("/api/channels/" + channelId + "/name")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"name\":\"  lounge  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(channelId))
                .andExpect(jsonPath("$.name").value("lounge"));
        assertEquals("lounge", channelRepository.findById(channelId).orElseThrow().getName());

        // validation: blank -> 400, over-100 -> 400, unknown id -> 404
        mockMvc.perform(patch("/api/channels/" + channelId + "/name")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/channels/" + channelId + "/name")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"name\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/channels/999999/name")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"name\":\"ghost\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void roleNameAllowsAnyAlphabet_butRejectsSpacesAndSymbols() throws Exception {
        String ownerToken = registerAndLogin("alphabetowner", "supersecret");
        byte[] cyrillic = "{\"name\":\"Модератор\",\"permissions\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content(cyrillic))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"name\":\"has space\",\"permissions\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + ownerToken)
                        .contentType(JSON).content("{\"name\":\"punct!ation\",\"permissions\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
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
