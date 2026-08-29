package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.repository.RoleRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the self-rename ({@code PATCH /api/users/me/username}). The defining property
 * is token coupling: the JWT subject is the username, so a rename must return a FRESH pair (all
 * refresh tokens revoked) while the pre-rename access token dies instantly. Permission enforcement
 * (CHANGE_NICKNAME, granted to @everyone by the seeder defaults) is exercised by stripping the bit
 * from the stored @everyone row and letting {@link RoleSeeder#ensureEveryoneChangeNickname()}
 * repair it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UsernameChangeIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleSeeder roleSeeder;

    @Test
    void renameRotatesTokensAndOldOnesDie() throws Exception {
        registerAndLogin("alice", "supersecret");
        MvcResult aliceLogin = login("alice", "supersecret");
        String oldAccess = JsonPath.read(aliceLogin.getResponse().getContentAsString(), "$.token");
        String oldRefresh = JsonPath.read(aliceLogin.getResponse().getContentAsString(), "$.refreshToken");
        String bobToken = registerAndLogin("bob", "supersecret");   // a second member for the reuse check

        MvcResult result = mockMvc.perform(patch("/api/users/me/username")
                        .header("Authorization", "Bearer " + oldAccess)
                        .contentType(JSON).content("{\"username\":\"alice2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("alice2"))
                .andExpect(jsonPath("$.auth.token").isNotEmpty())
                .andExpect(jsonPath("$.auth.refreshToken").isNotEmpty())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String newAccess = JsonPath.read(body, "$.auth.token");
        String newRefresh = JsonPath.read(body, "$.auth.refreshToken");
        assertNotEquals(oldAccess, newAccess);

        // the pre-rename access token no longer resolves (subject = old username)
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + oldAccess))
                .andExpect(status().isUnauthorized());
        // the pre-rename refresh family was revoked by the rename
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON)
                        .content(String.format("{\"refreshToken\":\"%s\"}", oldRefresh)))
                .andExpect(status().isUnauthorized());
        // the fresh pair is a usable session: access resolves, refresh rotates cleanly
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice2"));
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON)
                        .content(String.format("{\"refreshToken\":\"%s\"}", newRefresh)))
                .andExpect(status().isOk());

        // the old name is free again: bob can take it (uniqueness is by name, not identity)
        mockMvc.perform(patch("/api/users/me/username")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"username\":\"alice\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateRejectedWithConflict() throws Exception {
        registerAndLogin("alice", "supersecret");
        String bobToken = registerAndLogin("bob", "supersecret");
        mockMvc.perform(patch("/api/users/me/username")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"username\":\"alice\"}"))
                .andExpect(status().isConflict());
        // and the member is unchanged
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.username").value("bob"));
    }

    @Test
    void validationRejectsBlankShortLongAndUnchanged() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        mockMvc.perform(patch("/api/users/me/username").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"username\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/users/me/username").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"username\":\"ab\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/users/me/username").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"username\":\"" + "x".repeat(33) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/users/me/username").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"username\":\"alice\"}"))
                .andExpect(status().isBadRequest());   // same name — nothing to do
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(patch("/api/users/me/username")
                        .contentType(JSON).content("{\"username\":\"carol\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionForbiddenAndSeederRepairsTheBit() throws Exception {
        registerAndLogin("alice", "supersecret");   // OWNER
        String bobToken = registerAndLogin("bob", "supersecret");

        // strip CHANGE_NICKNAME from @everyone — bob holds no other role, so the rename must 403
        Role everyone = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        everyone.setPermissions(everyone.getPermissions() & ~Permission.CHANGE_NICKNAME.bit);
        roleRepository.save(everyone);
        mockMvc.perform(patch("/api/users/me/username").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"username\":\"bobby\"}"))
                .andExpect(status().isForbidden());

        // the seeder's boot-time repair re-grants the bit for databases seeded before it defaulted
        roleSeeder.ensureEveryoneChangeNickname();
        Role repaired = roleRepository.findByKind(RoleKind.EVERYONE).orElseThrow();
        assertTrue((repaired.getPermissions() & Permission.CHANGE_NICKNAME.bit) != 0,
                "ensureEveryoneChangeNickname must re-grant the bit");
        assertFalse(repaired.getPermissions() == 0);

        // and with the bit back, bob's rename goes through
        MvcResult repairedRename = mockMvc.perform(
                        patch("/api/users/me/username").header("Authorization", "Bearer " + bobToken)
                                .contentType(JSON).content("{\"username\":\"bobby\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("bobby", JsonPath.read(repairedRename.getResponse().getContentAsString(),
                "$.user.username"));
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isCreated());
        return JsonPath.read(login(username, password).getResponse().getContentAsString(), "$.token");
    }

    private MvcResult login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();
    }
}
