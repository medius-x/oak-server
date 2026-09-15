package oak.server;

import oak.server.dto.MentionResponse;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.service.AuthorizationService;
import oak.server.service.MentionParser;
import oak.server.websocket.PresenceTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link MentionParser} with mocked repositories — the integration test covers
 * the USER/EVERYONE/HERE path through the real message pipeline; here we isolate ROLE resolution,
 * the non-mentionable-role fall-through to a same-named user, offset math for duplicates, and
 * {@link MentionParser#expandToUserIds}.
 */
@ExtendWith(MockitoExtension.class)
class MentionParserTest {

    @Mock private RoleRepository roleRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthorizationService auth;
    @Mock private PresenceTracker presenceTracker;

    @InjectMocks private MentionParser parser;

    @Test
    void parsesUserMentionWithOffsets() {
        User author = user(1L, "alice");
        when(auth.hasInChannel(author, null, Permission.MENTION_EVERYONE)).thenReturn(true);
        when(userRepository.findByNickname("bob")).thenReturn(Optional.of(user(2L, "bob")));

        List<MentionResponse> mentions = parser.parse("hi @bob", author, null);

        assertEquals(1, mentions.size());
        assertMention(mentions.get(0), "USER", 2L, "bob", 3, 7);
    }

    @Test
    void cyrillicNicknameIsMentionable() {
        User author = user(1L, "alice");
        when(auth.hasInChannel(author, null, Permission.MENTION_EVERYONE)).thenReturn(true);
        User bob = user(2L, "vpupkin");
        bob.setNickname("Вася");
        when(userRepository.findByNickname("Вася")).thenReturn(Optional.of(bob));

        List<MentionResponse> mentions = parser.parse("привет @Вася", author, null);

        assertEquals(1, mentions.size());
        assertMention(mentions.get(0), "USER", 2L, "Вася", 7, 12);
    }

    @Test
    void loginIsNeverMatchable() {
        User author = user(1L, "alice");
        when(auth.hasInChannel(author, null, Permission.MENTION_EVERYONE)).thenReturn(true);
        // vpupkin is the LOGIN of a user whose nickname is Вася — the login must not resolve.
        when(userRepository.findByNickname("vpupkin")).thenReturn(Optional.empty());

        assertTrue(parser.parse("@vpupkin", author, null).isEmpty());
    }

    @Test
    void everyoneRequiresPermission() {
        User author = user(1L, "alice");
        when(auth.hasInChannel(author, null, Permission.MENTION_EVERYONE)).thenReturn(false);

        assertTrue(parser.parse("@everyone hi", author, null).isEmpty());
    }

    @Test
    void everyoneResolvedWhenPermitted() {
        User author = user(1L, "alice");
        when(auth.hasInChannel(author, null, Permission.MENTION_EVERYONE)).thenReturn(true);
        when(roleRepository.findByKind(RoleKind.EVERYONE)).thenReturn(Optional.of(role(99L, "@everyone", RoleKind.EVERYONE, false)));

        List<MentionResponse> mentions = parser.parse("@everyone hi", author, null);

        assertEquals(1, mentions.size());
        assertEquals("EVERYONE", mentions.get(0).type());
        assertEquals(99L, mentions.get(0).id());
    }

    @Test
    void hereResolvedWhenPermitted() {
        User author = user(1L, "alice");
        when(auth.hasInChannel(author, null, Permission.MENTION_EVERYONE)).thenReturn(true);

        List<MentionResponse> mentions = parser.parse("@here folks", author, null);

        assertEquals(1, mentions.size());
        assertEquals("HERE", mentions.get(0).type());
        assertNull(mentions.get(0).id());
    }

    @Test
    void mentionableRoleResolved() {
        User author = user(1L, "alice");
        when(auth.hasInChannel(author, null, Permission.MENTION_EVERYONE)).thenReturn(false);
        when(roleRepository.findByName("devs")).thenReturn(Optional.of(role(10L, "devs", RoleKind.CUSTOM, true)));

        List<MentionResponse> mentions = parser.parse("@devs unite", author, null);

        assertEquals(1, mentions.size());
        assertMention(mentions.get(0), "ROLE", 10L, "devs", 0, 5);
    }

    @Test
    void nonMentionableRoleFallsThroughToSameNamedUser() {
        User author = user(1L, "alice");
        when(auth.hasInChannel(author, null, Permission.MENTION_EVERYONE)).thenReturn(false);
        when(roleRepository.findByName("devs")).thenReturn(Optional.of(role(10L, "devs", RoleKind.CUSTOM, false)));
        when(userRepository.findByNickname("devs")).thenReturn(Optional.of(user(20L, "devs")));

        List<MentionResponse> mentions = parser.parse("@devs", author, null);

        // A non-mentionable role does not consume the token, so a same-named user is @mentioned instead.
        assertEquals(1, mentions.size());
        assertMention(mentions.get(0), "USER", 20L, "devs", 0, 5);
    }

    @Test
    void unknownTokenProducesNoMention() {
        User author = user(1L, "alice");
        when(auth.hasInChannel(author, null, Permission.MENTION_EVERYONE)).thenReturn(true);
        when(roleRepository.findByName("ghost")).thenReturn(Optional.empty());
        when(userRepository.findByNickname("ghost")).thenReturn(Optional.empty());

        assertTrue(parser.parse("@ghost", author, null).isEmpty());
    }

    @Test
    void duplicateMentionsKeepDistinctOffsets() {
        User author = user(1L, "alice");
        when(auth.hasInChannel(author, null, Permission.MENTION_EVERYONE)).thenReturn(true);
        when(userRepository.findByNickname("bob")).thenReturn(Optional.of(user(2L, "bob")));

        List<MentionResponse> mentions = parser.parse("@bob @bob", author, null);

        assertEquals(2, mentions.size());
        assertEquals(0, mentions.get(0).start());   // first @bob: [0,4)
        assertEquals(5, mentions.get(1).start());   // second @bob: [5,9)
    }

    @Test
    void emptyAndNullContentReturnEmpty() {
        User author = user(1L, "alice");
        assertTrue(parser.parse(null, author, null).isEmpty());
        assertTrue(parser.parse("", author, null).isEmpty());
    }

    @Test
    void expandUserMention() {
        Set<Long> ids = parser.expandToUserIds(List.of(new MentionResponse("USER", 2L, "bob", 0, 4)));
        assertEquals(Set.of(2L), ids);
    }

    @Test
    void expandRoleMention() {
        when(userRepository.findUsersByRoleId(10L)).thenReturn(List.of(user(1L, "a"), user(2L, "b")));

        Set<Long> ids = parser.expandToUserIds(List.of(new MentionResponse("ROLE", 10L, "devs", 0, 4)));
        assertEquals(Set.of(1L, 2L), ids);
    }

    @Test
    void expandEveryoneMention() {
        when(userRepository.findAll()).thenReturn(List.of(user(1L, "a"), user(2L, "b")));

        Set<Long> ids = parser.expandToUserIds(List.of(new MentionResponse("EVERYONE", null, null, 0, 9)));
        assertEquals(Set.of(1L, 2L), ids);
    }

    @Test
    void expandHereMention() {
        when(presenceTracker.onlineUsernames()).thenReturn(Set.of("a", "b"));
        when(userRepository.findByUsername("a")).thenReturn(Optional.of(user(1L, "a")));
        when(userRepository.findByUsername("b")).thenReturn(Optional.of(user(2L, "b")));

        Set<Long> ids = parser.expandToUserIds(List.of(new MentionResponse("HERE", null, null, 0, 5)));
        assertEquals(Set.of(1L, 2L), ids);
    }

    private static void assertMention(MentionResponse actual, String type, long id, String name, int start, int end) {
        assertEquals(type, actual.type());
        assertEquals(id, actual.id());
        assertEquals(name, actual.name());
        assertEquals(start, actual.start());
        assertEquals(end, actual.end());
    }

    private static User user(long id, String username) {
        User user = new User(username, "hash");
        user.setId(id);
        return user;
    }

    private static Role role(long id, String name, RoleKind kind, boolean mentionable) {
        Role role = new Role(name, 1, 0L, mentionable, false, kind);
        role.setId(id);
        return role;
    }
}
