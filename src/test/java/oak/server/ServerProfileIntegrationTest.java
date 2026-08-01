package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.ServerProfile;
import oak.server.repository.ServerProfileRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the instance-identity endpoints on the in-memory H2 test profile. {@code GET}
 * is open to any member and returns the seeder default; every mutation (rename + the two-phase icon
 * upload) requires {@code MANAGE_SERVER} — so the first registered member (OWNER) can edit, a second
 * ordinary member cannot. MinIO is disabled here (noop signer), so {@code avatarUrl} is null and the
 * test asserts the keys persist on the singleton row instead. Mirrors {@code UserAvatarIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ServerProfileIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServerProfileRepository serverProfileRepository;

    @Test
    void getRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/server")).andExpect(status().isUnauthorized());
    }

    @Test
    void getReturnsSeededDefault() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        mockMvc.perform(get("/api/server").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("OAK Server"))
                .andExpect(jsonPath("$.avatarKey").isEmpty())   // no icon set
                .andExpect(jsonPath("$.avatarUrl").isEmpty());  // noop signer
    }

    @Test
    void ownerCanRenameUploadIconLazyOriginalAndRemove() throws Exception {
        String token = registerAndLogin("alice", "supersecret");   // alice = first = OWNER

        // rename
        mockMvc.perform(patch("/api/server").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"name\":\"My OAK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My OAK"));
        assertEquals("My OAK", serverProfileRepository.singleton().getName());

        // two-phase icon: slot both, reject a non-image, then confirm
        String iconKey = slotKey(token, "/api/server/avatar", "icon.png");
        String originalKey = slotKey(token, "/api/server/avatar/original", "photo.JPG");
        mockMvc.perform(post("/api/server/avatar").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"fileName\":\"notes.txt\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/server/avatar/confirm").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(String.format(
                                "{\"avatarKey\":\"%s\",\"originalKey\":\"%s\"}", iconKey, originalKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My OAK"));
        ServerProfile profile = serverProfileRepository.singleton();
        assertEquals(iconKey, profile.getAvatarKey());
        assertEquals(originalKey, profile.getAvatarOriginalKey());

        // lazy original -> 200 (url null under the noop signer, but the key is present)
        mockMvc.perform(get("/api/server/avatar/original").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // remove clears both keys
        mockMvc.perform(delete("/api/server/avatar").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        ServerProfile after = serverProfileRepository.singleton();
        assertNull(after.getAvatarKey());
        assertNull(after.getAvatarOriginalKey());

        // now the lazy original endpoint is 404
        mockMvc.perform(get("/api/server/avatar/original").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonOwnerCanReadButNotMutate() throws Exception {
        registerAndLogin("alice", "supersecret");               // alice = OWNER
        String bobToken = registerAndLogin("bob", "supersecret"); // bob = ordinary (no roles)

        // bob can read the profile
        mockMvc.perform(get("/api/server").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        // bob cannot rename
        mockMvc.perform(patch("/api/server").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isForbidden());

        // bob cannot touch the icon: slot / confirm / remove / lazy-original all 403
        mockMvc.perform(post("/api/server/avatar").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"fileName\":\"icon.png\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/server/avatar/confirm").header("Authorization", "Bearer " + bobToken)
                        .contentType(JSON).content("{\"avatarKey\":\"x\",\"originalKey\":null}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/server/avatar").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/server/avatar/original").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    private String slotKey(String token, String endpoint, String fileName) throws Exception {
        MvcResult result = mockMvc.perform(post(endpoint).header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(String.format("{\"fileName\":\"%s\"}", fileName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").isNotEmpty())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.key");
    }

    private String registerAndLogin(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
    }
}
