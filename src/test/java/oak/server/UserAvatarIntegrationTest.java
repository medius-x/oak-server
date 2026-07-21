package oak.server;

import com.jayway.jsonpath.JsonPath;
import oak.server.entity.User;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the avatar flow on the in-memory H2 test profile: two-phase upload (avatar +
 * original slots, then confirm both keys), the lazy original endpoint, and remove. MinIO is disabled
 * here (noop signer), so {@code uploadUrl}/{@code url} are null — the test asserts the keys persist
 * on the account instead. Mirrors {@code AttachmentIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserAvatarIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    void avatarSlotsRequireAuth() throws Exception {
        mockMvc.perform(post("/api/users/me/avatar").contentType(JSON).content("{\"fileName\":\"me.png\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/me/avatar/original").contentType(JSON).content("{\"fileName\":\"me.png\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void avatarFlowMintsSlotsValidatesTypeBindsBothKeysLazyOriginalAndRemoves() throws Exception {
        String token = registerAndLogin("alice", "supersecret");

        // avatar slot (PNG) -> 200 + key (uploadUrl is null under the noop signer)
        String avatarKey = slotKey(token, "/api/users/me/avatar", "me.png");
        // original slot (JPG, case-insensitive) -> 200 + key
        String originalKey = slotKey(token, "/api/users/me/avatar/original", "photo.JPG");
        // a non-image extension is rejected at the slot step — the server never sees the bytes
        mockMvc.perform(post("/api/users/me/avatar").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"fileName\":\"notes.txt\"}"))
                .andExpect(status().isBadRequest());

        // confirm binds both keys to the account
        mockMvc.perform(post("/api/users/me/avatar/confirm").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(String.format(
                                "{\"avatarKey\":\"%s\",\"originalKey\":\"%s\"}", avatarKey, originalKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));

        User alice = userRepository.findByUsername("alice").orElseThrow();
        assertEquals(avatarKey, alice.getAvatarKey());
        assertEquals(originalKey, alice.getAvatarOriginalKey());

        // lazy original endpoint -> 200 (url null under the noop signer, but the key is present)
        mockMvc.perform(get("/api/users/me/avatar/original").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // remove clears both keys
        mockMvc.perform(delete("/api/users/me/avatar").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        User after = userRepository.findByUsername("alice").orElseThrow();
        assertNull(after.getAvatarKey());
        assertNull(after.getAvatarOriginalKey());

        // now the lazy original endpoint is 404 (no avatar/original)
        mockMvc.perform(get("/api/users/me/avatar/original").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
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
