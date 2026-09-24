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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the MinIO attachment flow on the in-memory H2 test profile. MinIO is
 * disabled here (noop signer), so {@code uploadUrl} is null — the real upload/download is
 * exercised only by the end-to-end smoke test. Mirrors {@code ChannelMessageFlowIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttachmentIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void attachmentEndpointRequiresAuthIssuesKeyAndMessageAcceptsAttachment() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(token, "general", "TEXT");

        // requesting an upload slot without a token -> 401
        mockMvc.perform(post("/api/attachments").contentType(JSON).content("{\"fileName\":\"pic.png\"}"))
                .andExpect(status().isUnauthorized());

        // with a token -> 200 + a non-empty object key (uploadUrl is null under the noop signer)
        mockMvc.perform(post("/api/attachments").header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"fileName\":\"pic.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").isNotEmpty());

        // an attachment-only message (empty text) is valid -> 201; the wire carries the list form
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"content\":\"\",\"attachments\":[{\"key\":\"some-key\",\"fileName\":\"pic.png\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].fileName").value("pic.png"))
                .andExpect(jsonPath("$.attachments[0].key").value("some-key"))
                .andExpect(jsonPath("$.authorUsername").value("alice"));

        // a message with neither content nor attachments -> 400

        // a message with neither content nor attachment -> 400
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON).content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    /** The intrinsic-dimension passthrough (2026-09-25): the client measures
     *  width/height at upload; the server stores and echoes them verbatim so
     *  the client can reserve the exact layout box before the bytes arrive.
     *  Absent fields stay null (pre-dimension rows). */
    @Test
    void attachmentDimensionsRoundTripAndAbsentStaysNull() throws Exception {
        String token = registerAndLogin("alice", "supersecret");
        Long channelId = createChannel(token, "general", "TEXT");
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"content\":null,\"attachments\":["
                                + "{\"key\":\"vid-key\",\"fileName\":\"clip.mp4\",\"width\":1920,\"height\":1080},"
                                + "{\"key\":\"pic-key\",\"fileName\":\"shot.png\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].width").value(1920))
                .andExpect(jsonPath("$.attachments[0].height").value(1080))
                .andExpect(jsonPath("$.attachments[1].width").doesNotExist())
                .andExpect(jsonPath("$.attachments[1].height").doesNotExist());
        // the persisted JSON column carries them too (history path)
        mockMvc.perform(get("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachments[0].width").value(1920))
                .andExpect(jsonPath("$[0].attachments[1].width").doesNotExist());
    }

    /** The fresh-link re-mint (a streamed playback that outlived its hour-old presigned URL):
     *  auth-gated, 404 on an unknown key, and for a key a message actually references it
     *  returns the re-signed URL field (null under the noop signer — structurally present). */
    @Test
    void freshLinkRequiresAuthKnownKeyAndReSigns() throws Exception {
        String token = registerAndLogin("bob", "supersecret");
        Long channelId = createChannel(token, "media", "TEXT");
        mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"content\":\"\",\"attachments\":[{\"key\":\"attachments/abc/song.mp3\",\"fileName\":\"song.mp3\"}]}"))
                .andExpect(status().isCreated());

        // no token -> 401
        mockMvc.perform(get("/api/attachments/link?key=attachments/abc/song.mp3"))
                .andExpect(status().isUnauthorized());

        // unknown key -> 404 (the slashes in the key are why this is a query param)
        mockMvc.perform(get("/api/attachments/link?key=attachments/xyz/missing.mp4")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/attachments/link")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // the referenced key -> 200 with the url field present (null under the noop signer —
        // MockMvc's jsonPath().exists() treats an explicit null as absent, so read it directly)
        MvcResult freshLink = mockMvc.perform(get("/api/attachments/link?key=attachments/abc/song.mp3")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertNull(JsonPath.read(freshLink.getResponse().getContentAsString(), "$.url"),
                "the re-signed URL is structurally present (null only under the noop signer)");
    }

    /** The video-preview wire fields: a message posted with {@code thumbnailKey} +
     *  {@code durationMillis} echoes them out, and the THUMBNAIL key itself resolves through
     *  the fresh-link endpoint (the native LIKE over the JSON column finds keys in any field —
     *  a preview overrunning its hour-old URL re-mints like any attachment). */
    @Test
    void videoPreviewFieldsRoundTripAndThumbnailKeyResolvesFreshLink() throws Exception {
        String token = registerAndLogin("carol", "supersecret");
        Long channelId = createChannel(token, "video", "TEXT");
        MvcResult created = mockMvc.perform(post("/api/channels/" + channelId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"content\":\"\",\"attachments\":[{\"key\":\"attachments/abc/clip.mp4\","
                                + "\"fileName\":\"clip.mp4\",\"thumbnailKey\":\"attachments/def/clip-thumb.jpg\","
                                + "\"durationMillis\":65000}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].thumbnailKey").value("attachments/def/clip-thumb.jpg"))
                .andExpect(jsonPath("$.attachments[0].durationMillis").value(65000))
                .andReturn();
        assertNull(JsonPath.read(created.getResponse().getContentAsString(), "$.attachments[0].thumbnailUrl"),
                "thumbnailUrl is structurally present (null only under the noop signer)");

        // the preview's OWN key re-mints through the same capability model
        MvcResult freshLink = mockMvc.perform(
                        get("/api/attachments/link?key=attachments/def/clip-thumb.jpg")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertNull(JsonPath.read(freshLink.getResponse().getContentAsString(), "$.url"),
                "the preview's fresh link is structurally present (null only under the noop signer)");
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

    private Long createChannel(String token, String name, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(String.format("{\"name\":\"%s\",\"type\":\"%s\"}", name, type)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
