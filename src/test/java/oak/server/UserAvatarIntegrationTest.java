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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the two-phase avatar upload (mint a MinIO slot, confirm the key, remove) on
 * the in-memory H2 test profile. MinIO is disabled here (noop signer), so {@code uploadUrl} and
 * {@code avatarUrl} are null — the test asserts the key is persisted on the account instead. Mirrors
 * {@code AttachmentIntegrationTest}.
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
    void avatarUploadSlotRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/users/me/avatar").contentType(JSON).content("{\"fileName\":\"me.png\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void avatarSlotMintsKeyForImagesRejectsOtherTypesAndConfirmBindsKey() throws Exception {
        String token = registerAndLogin("alice", "supersecret");

        // PNG accepted -> 200 + non-empty key (uploadUrl is null under the noop signer)
        MvcResult png = mockMvc.perform(post("/api/users/me/avatar").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"fileName\":\"me.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").isNotEmpty())
                .andReturn();
        String key = JsonPath.read(png.getResponse().getContentAsString(), "$.key");

        // JPG is accepted too (extension check is case-insensitive)
        mockMvc.perform(post("/api/users/me/avatar").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"fileName\":\"photo.JPG\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").isNotEmpty());

        // a non-image extension is rejected at the slot step — the server never sees the bytes, so
        // this name check is the only server-side type gate
        mockMvc.perform(post("/api/users/me/avatar").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"fileName\":\"notes.txt\"}"))
                .andExpect(status().isBadRequest());

        // confirm binds the freshly-uploaded key to the account
        mockMvc.perform(post("/api/users/me/avatar/confirm").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(String.format("{\"key\":\"%s\"}", key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));

        assertEquals(key, userRepository.findByUsername("alice").orElseThrow().getAvatarKey());

        // remove clears it
        mockMvc.perform(delete("/api/users/me/avatar").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertNull(userRepository.findByUsername("alice").orElseThrow().getAvatarKey());
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
