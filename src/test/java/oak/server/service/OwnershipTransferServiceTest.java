package oak.server.service;

import oak.server.dto.MemberEvent;
import oak.server.dto.OwnershipEvent;
import oak.server.dto.OwnershipTransferResponse;
import oak.server.entity.OwnershipTransfer;
import oak.server.entity.OwnershipTransferStatus;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.OwnershipTransferRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of the accept path: the role swap's ORDER (the old owner loses OWNER
 * before the recipient gains it, both saved), both members events firing so every
 * client refetches, and both parties' ownership pushes. Plain Mockito.
 */
@ExtendWith(MockitoExtension.class)
class OwnershipTransferServiceTest {

    @Mock
    private OwnershipTransferRepository transferRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private AuthorizationService auth;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private OwnershipTransferService service;

    @Test
    void acceptSwapsTheRoleAndBroadcastsToBothSides() {
        User from = user(1L, "oldowner");
        User to = user(2L, "newowner");
        Role ownerRole = new Role("OWNER", 0, 0L, false, false, RoleKind.OWNER);
        ownerRole.setId(3L);
        OwnershipTransfer transfer = new OwnershipTransfer(from, to, OwnershipTransferStatus.PENDING);
        when(auth.requireUser("newowner")).thenReturn(to);
        when(transferRepository.findById(5L)).thenReturn(Optional.of(transfer));
        when(auth.isOwner(from)).thenReturn(true);
        when(roleRepository.findByKind(RoleKind.OWNER)).thenReturn(Optional.of(ownerRole));

        OwnershipTransferResponse response = service.accept("newowner", 5L);

        assertEquals(OwnershipTransferStatus.ACCEPTED, transfer.getStatus());
        assertTrue(from.getRoles().isEmpty());
        assertTrue(to.getRoles().contains(ownerRole));
        assertEquals("newowner", response.toUsername());

        // the swap is persisted before the broadcasts, and BOTH member events fire
        InOrder order = inOrder(userRepository, transferRepository, messagingTemplate);
        order.verify(userRepository).save(from);
        order.verify(userRepository).save(to);
        order.verify(transferRepository).save(transfer);
        order.verify(messagingTemplate).convertAndSend(eq("/topic/members"), eq(new MemberEvent("rolesChanged", 1L)));
        order.verify(messagingTemplate).convertAndSend(eq("/topic/members"), eq(new MemberEvent("rolesChanged", 2L)));
        // both parties get the ownership lifecycle push
        verify(messagingTemplate).convertAndSendToUser(eq("oldowner"), eq(OwnershipTransferService.OWNERSHIP_QUEUE),
                eq(OwnershipEvent.of("ACCEPTED", response)));
        verify(messagingTemplate).convertAndSendToUser(eq("newowner"), eq(OwnershipTransferService.OWNERSHIP_QUEUE),
                any(OwnershipEvent.class));
    }

    @Test
    void acceptRefusesANonPendingTransferWithoutTouchingAnything() {
        User from = user(1L, "oldowner");
        User to = user(2L, "newowner");
        OwnershipTransfer transfer = new OwnershipTransfer(from, to, OwnershipTransferStatus.DECLINED);
        when(auth.requireUser("newowner")).thenReturn(to);
        when(transferRepository.findById(5L)).thenReturn(Optional.of(transfer));

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.accept("newowner", 5L));

        verify(userRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    private static User user(Long id, String username) {
        User user = new User(username, "hash");
        user.setId(id);
        return user;
    }
}
