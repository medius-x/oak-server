package oak.server.service;

import oak.server.config.JanusProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link HttpJanusVoiceAccessService} against a mocked {@link JanusAdminClient}:
 * token minting/bookkeeping (cap + per-(user,channel) scoping of revocation), the pure display/audio-mid
 * matching against fixture {@code joined}-event JSON, and the HMAC room credentials. The executor-driven
 * forced actions are covered through their package-private synchronous core where practical; the full
 * control-session flow is smoke-tested against a live gateway.
 */
@ExtendWith(MockitoExtension.class)
class JanusVoiceAccessServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private JanusAdminClient client;

    private HttpJanusVoiceAccessService service;

    @BeforeEach
    void setUp() {
        JanusProperties properties = new JanusProperties(
                "http://localhost:8088/janus", "ws://localhost:8188", "unit-secret",
                "http://localhost:7088/admin", true);
        service = new HttpJanusVoiceAccessService(client, properties);
    }

    private static JsonNode json(String fixture) {
        return MAPPER.readTree(fixture);
    }

    private static JsonNode success() {
        return json("{\"janus\":\"success\"}");
    }

    // --- token minting & bookkeeping ---

    @Test
    void issueVoiceTokenRegistersWithTheGateway() {
        when(client.addToken(anyString())).thenReturn(success());

        String token = service.issueVoiceToken("alice", 5L);

        assertNotNull(token);
        assertEquals(64, token.length());
        verify(client).addToken(token);
        verify(client, never()).removeToken(anyString());
    }

    @Test
    void issueVoiceTokenThrowsWhenTheGatewayRejects() {
        when(client.addToken(anyString()))
                .thenReturn(json("{\"janus\":\"error\",\"error\":{\"code\":403,\"reason\":\"Unauthorized request\"}}"));

        assertThrows(JanusAdminException.class, () -> service.issueVoiceToken("alice", 5L));
    }

    @Test
    void tokenHistoryIsCappedAndTheOldestTokenIsRevoked() {
        when(client.addToken(anyString())).thenReturn(success());

        for (int minted = 0; minted < 10; minted++) {
            service.issueVoiceToken("alice", 5L);
        }

        // 10 minted, cap 8 -> the 2 oldest were evicted with remove_token.
        verify(client, times(10)).addToken(anyString());
        verify(client, times(2)).removeToken(anyString());
    }

    @Test
    void revokeTokensRemovesOnlyThatUserChannelPair() {
        when(client.addToken(anyString())).thenReturn(success());
        String aliceRoom5 = service.issueVoiceToken("alice", 5L);
        String aliceRoom6 = service.issueVoiceToken("alice", 6L);
        String bobRoom5 = service.issueVoiceToken("bob", 5L);

        // The synchronous core — the public method only wraps it in the executor.
        service.revokeTokensNow(5L, "alice");

        verify(client).removeToken(aliceRoom5);
        verify(client, never()).removeToken(aliceRoom6);
        verify(client, never()).removeToken(bobRoom5);
    }

    // --- room credentials ---

    @Test
    void roomPinIsDeterministicAndDistinctFromTheSecretDomain() {
        assertEquals(service.roomPin(5L), service.roomPin(5L));
        assertNotEquals(service.roomPin(5L), service.roomPin(6L));
        assertNotEquals(service.roomPin(5L), HttpJanusVoiceAccessService.hmacSha256(
                "unit-secret", "oak-voice-secret:5"));
    }

    @Test
    void hmacIsStableAndHexEncoded() {
        assertEquals(HttpJanusVoiceAccessService.hmacSha256("secret", "payload"),
                HttpJanusVoiceAccessService.hmacSha256("secret", "payload"));
        assertEquals(32, HttpJanusVoiceAccessService.hmacSha256("secret", "payload").length());
    }

    // --- publisher matching (fixtures of the joined event's publishers array) ---

    @Test
    void matchingPublishersFiltersByDisplayAndTakesAllSessionsOfTheUser() {
        JsonNode publishers = json("""
                [
                  {"id": 111, "display": "victim",   "streams": [{"type": "audio", "mid": "0"}]},
                  {"id": 222, "display": "someone",  "streams": [{"type": "audio", "mid": "0"}]},
                  {"id": 333, "display": "victim",   "streams": [{"type": "audio", "mid": "0"}]}
                ]
                """);

        List<JsonNode> matched = HttpJanusVoiceAccessService.matchingPublishers(publishers, "victim");

        assertEquals(2, matched.size());
        assertEquals(111, matched.get(0).path("id").asLong());
        assertEquals(333, matched.get(1).path("id").asLong());
    }

    @Test
    void audioMidOfPicksTheFirstAudioStream() {
        assertEquals("1", HttpJanusVoiceAccessService.audioMidOf(json(
                "{\"id\":1,\"streams\":[{\"type\":\"video\",\"mid\":\"0\"},{\"type\":\"audio\",\"mid\":\"1\"}]}")));
        assertNull(HttpJanusVoiceAccessService.audioMidOf(json(
                "{\"id\":1,\"streams\":[{\"type\":\"video\",\"mid\":\"0\"}]}")));
        assertNull(HttpJanusVoiceAccessService.audioMidOf(json("{\"id\":1}")));
    }

    // --- control-session core ---

    @Test
    void ensureRoomCreatesOnlyWhenAbsent() {
        when(client.addToken(anyString())).thenReturn(success());
        when(client.createSession(anyString())).thenReturn(1L);
        when(client.attach(anyLong(), anyString())).thenReturn(2L);
        // exists -> false: the room must be created
        when(client.message(anyLong(), anyLong(), any(), anyString())).thenReturn(json(
                "{\"janus\":\"success\",\"plugindata\":{\"data\":{\"videoroom\":\"success\",\"exists\":false}}}"));

        service.ensureRoom(5L);

        verify(client).message(eq(1L), eq(2L),
                argThat(body -> "create".equals(body.get("request"))), anyString());
    }

    @Test
    void ensureRoomSkipsCreateWhenTheRoomExists() {
        when(client.addToken(anyString())).thenReturn(success());
        when(client.createSession(anyString())).thenReturn(1L);
        when(client.attach(anyLong(), anyString())).thenReturn(2L);
        when(client.message(anyLong(), anyLong(), any(), anyString())).thenReturn(json(
                "{\"janus\":\"success\",\"plugindata\":{\"data\":{\"videoroom\":\"success\",\"exists\":true}}}"));

        service.ensureRoom(5L);

        verify(client, never()).message(anyLong(), anyLong(),
                argThat(body -> "create".equals(body.get("request"))), anyString());
    }
}
