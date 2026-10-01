package oak.server.service;

import oak.server.controller.UserController;
import oak.server.dto.MemberEvent;
import oak.server.dto.UserSummary;
import oak.server.entity.User;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.websocket.VoiceAccessEnforcer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of "remove from server": the victim's disconnect push carries {@code removed=true}
 * BEFORE the account is anonymized (live sessions still know the old username), the voice eviction
 * runs, the tombstone names free the original login/nickname (with the suffix retry on a
 * collision), and the members/users broadcasts go out. Plain Mockito — the repository, the
 * authorizer, the signer and the enforcer are mocked.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private AuthorizationService auth;
    @Mock
    private AttachmentUrlSigner signer;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private VoiceAccessEnforcer voiceEnforcer;
    @Mock
    private OwnershipTransferService ownershipTransferService;

    @InjectMocks
    private MemberService memberService;

    @Test
    void removePushesTheDisconnectThenAnonymizesAndBroadcasts() {
        User actor = user(1L, "admin");
        User victim = user(2L, "victim");
        when(auth.requireUser("admin")).thenReturn(actor);
        when(userRepository.findById(2L)).thenReturn(Optional.of(victim));
        when(auth.isOwner(victim)).thenReturn(false);
        when(userRepository.existsByUsername("deleted_2")).thenReturn(false);
        when(userRepository.existsByNickname("Deleted-User-2")).thenReturn(false);

        memberService.remove(2L, "admin");

        // the disconnect push goes to the OLD username and carries removed=true
        verify(messagingTemplate).convertAndSendToUser(eq("victim"), eq(MemberService.MEMBER_KICK_QUEUE),
                eq(Map.of("by", "admin", "banned", false, "removed", true)));
        // the victim is pulled out of every voice room they occupy
        verify(voiceEnforcer).evictEverywhere("victim", "admin");
        // the account is flagged and anonymized — the original login/nickname are freed
        assertThat(victim.isRemoved()).isTrue();
        assertThat(victim.getUsername()).isEqualTo("deleted_2");
        assertThat(victim.getNickname()).isEqualTo("Deleted-User-2");
        verify(userRepository).save(victim);
        // both broadcasts: the members refetch ping and the live roster repaint
        verify(messagingTemplate).convertAndSend(eq(MemberService.MEMBERS_TOPIC),
                eq(new MemberEvent("removed", 2L)));
        verify(messagingTemplate).convertAndSend(eq(UserController.USERS_TOPIC),
                eq(UserSummary.from(victim, signer)));
    }

    @Test
    void tombstoneNamesRetryWithASuffixOnACollision() {
        User actor = user(1L, "admin");
        User victim = user(3L, "victim");
        when(auth.requireUser("admin")).thenReturn(actor);
        when(userRepository.findById(3L)).thenReturn(Optional.of(victim));
        when(auth.isOwner(victim)).thenReturn(false);
        // someone already registered the exact tombstone login — the first candidate is taken
        // (the nickname check for attempt 0 is short-circuited away by the username collision)
        when(userRepository.existsByUsername("deleted_3")).thenReturn(true);
        when(userRepository.existsByUsername("deleted_3-2")).thenReturn(false);
        when(userRepository.existsByNickname("Deleted-User-3-2")).thenReturn(false);

        memberService.remove(3L, "admin");

        assertThat(victim.getUsername()).isEqualTo("deleted_3-2");
        assertThat(victim.getNickname()).isEqualTo("Deleted-User-3-2");
    }

    private static User user(Long id, String username) {
        User user = new User(username, "hash");
        user.setId(id);
        return user;
    }
}
