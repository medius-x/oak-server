package oak.server;

import oak.server.controller.UserController;
import oak.server.dto.MemberEvent;
import oak.server.dto.RegisterRequest;
import oak.server.dto.UserSummary;
import oak.server.entity.Invite;
import oak.server.repository.InviteRepository;
import oak.server.repository.RefreshTokenRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.security.JwtProperties;
import oak.server.security.JwtTokenProvider;
import oak.server.service.AuthService;
import oak.server.service.LoginRateLimiter;
import oak.server.service.MemberService;
import oak.server.service.ReadStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The registration roster broadcast ({@code /topic/users}): a new member must appear in
 * the roster of every ALREADY-CONNECTED client — without the push the owner's roster
 * keeps its login-time snapshot and a fresh invitee is invisible until a refetch
 * (live VPS 2026-10-13). Mockito unit (the MessageServiceTest idiom): the after-commit
 * arm of the push cannot fire inside a {@code @Transactional} integration test, so the
 * transaction synchronization is driven by hand here.
 */
class AuthServiceRegisterBroadcastTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final InviteRepository inviteRepository = mock(InviteRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final ReadStateService readStateService = mock(ReadStateService.class);

    private final AuthService authService = new AuthService(
            userRepository,
            mock(PasswordEncoder.class),
            mock(AuthenticationManager.class),
            mock(JwtTokenProvider.class),
            mock(RefreshTokenRepository.class),
            mock(JwtProperties.class),
            roleRepository,
            readStateService,
            inviteRepository,
            messagingTemplate,
            mock(LoginRateLimiter.class));

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void invitedRegistrationPushesTheNewMemberToBothLiveRosters() {
        when(userRepository.existsByUsername("newbie")).thenReturn(false);
        when(userRepository.existsByNickname("Newbie")).thenReturn(false);
        when(userRepository.count()).thenReturn(1L);
        Invite activeInvite = new Invite("s3cret-code", null, Instant.now().plusSeconds(600));
        when(inviteRepository.findBySecret("s3cret-code")).thenReturn(Optional.of(activeInvite));
        when(inviteRepository.claim(eq("s3cret-code"), any(Instant.class))).thenReturn(1);
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        authService.register(new RegisterRequest("newbie", "hunter2222", "Newbie", "s3cret-code"));

        ArgumentCaptor<UserSummary> pushed = ArgumentCaptor.forClass(UserSummary.class);
        verify(messagingTemplate).convertAndSend(eq(UserController.USERS_TOPIC), pushed.capture());
        assertEquals("newbie", pushed.getValue().username());
        assertEquals("Newbie", pushed.getValue().displayName());
        // The member pane (GET /api/members) refreshes off its own topic too — the
        // newcomer must appear there for every connected client, not just in the
        // display-name roster (live VPS 2026-10-13: the /topic/users push alone left
        // the owner's MembersPane without the fresh invitee).
        ArgumentCaptor<MemberEvent> memberEvent = ArgumentCaptor.forClass(MemberEvent.class);
        verify(messagingTemplate).convertAndSend(eq(MemberService.MEMBERS_TOPIC), memberEvent.capture());
        assertEquals("joined", memberEvent.getValue().type());
        // The read-state seeding still ran (the pushes are additive, not a replacement).
        verify(readStateService).seedInitialReadStates(any());
    }

    @Test
    void ownerBootstrapPushesToo() {
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(userRepository.existsByNickname("admin")).thenReturn(false);
        when(userRepository.count()).thenReturn(0L);
        when(roleRepository.findByKind(any())).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        authService.register(new RegisterRequest("admin", "hunter2222", null, null));

        // One path, no branching: the bootstrap member rides the same roster pushes
        // (nobody is connected on a fresh instance, so they are harmlessly undelivered).
        verify(messagingTemplate).convertAndSend(eq(UserController.USERS_TOPIC), any(UserSummary.class));
        verify(messagingTemplate).convertAndSend(eq(MemberService.MEMBERS_TOPIC), any(MemberEvent.class));
    }

    @Test
    void insideATransactionThePushWaitsForTheCommit() {
        when(userRepository.existsByUsername("newbie")).thenReturn(false);
        when(userRepository.existsByNickname("newbie")).thenReturn(false);
        when(userRepository.count()).thenReturn(1L);
        Invite activeInvite = new Invite("s3cret-code", null, Instant.now().plusSeconds(600));
        when(inviteRepository.findBySecret("s3cret-code")).thenReturn(Optional.of(activeInvite));
        when(inviteRepository.claim(eq("s3cret-code"), any(Instant.class))).thenReturn(1);
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            authService.register(new RegisterRequest("newbie", "hunter2222", null, "s3cret-code"));
            // Before the commit the roster would read without the INSERT — no push yet
            // (the invites-ping lesson: an in-tx push lets subscribers refetch stale state).
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(messagingTemplate).convertAndSend(eq(UserController.USERS_TOPIC), any(UserSummary.class));
            verify(messagingTemplate).convertAndSend(eq(MemberService.MEMBERS_TOPIC), any(MemberEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
