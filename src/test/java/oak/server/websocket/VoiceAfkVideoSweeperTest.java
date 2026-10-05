package oak.server.websocket;

import oak.server.dto.VoiceChannelMembersDto;
import oak.server.entity.ServerProfile;
import oak.server.repository.ServerProfileRepository;
import oak.server.service.JanusVoiceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M8: the AFK room's video-ban sweep — an OCCUPIED designated AFK room is probed with the
 * union of the two occupancy registries (tracker ∪ token holders); no designation or an
 * idle room costs nothing and never reaches the gateway.
 */
@ExtendWith(MockitoExtension.class)
class VoiceAfkVideoSweeperTest {

    @Mock
    private ServerProfileRepository profileRepository;
    @Mock
    private VoiceMembershipTracker tracker;
    @Mock
    private JanusVoiceAccessService voiceAccess;

    @InjectMocks
    private VoiceAfkVideoSweeper sweeper;

    @Test
    void probesAnOccupiedAfkRoomWithTheUnionOfBothRegistries() {
        ServerProfile profile = new ServerProfile("srv");
        profile.setAfkChannelId(7L);
        when(profileRepository.singleton()).thenReturn(profile);
        when(tracker.snapshot()).thenReturn(List.of(
                new VoiceChannelMembersDto(7L, List.of("alice"))));
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of(
                7L, List.of("bob"),   // the non-STOMP rogue half of the union
                9L, List.of("carol")));

        sweeper.sweep();

        verify(voiceAccess).kickVideoPublishers(7L, Set.of("alice", "bob"));
    }

    @Test
    void withoutADesignatedAfkChannelNothingIsProbed() {
        when(profileRepository.singleton()).thenReturn(new ServerProfile("srv"));

        sweeper.sweep();

        verifyNoInteractions(tracker, voiceAccess);
    }

    @Test
    void anIdleAfkRoomCostsNothing() {
        ServerProfile profile = new ServerProfile("srv");
        profile.setAfkChannelId(7L);
        when(profileRepository.singleton()).thenReturn(profile);
        when(tracker.snapshot()).thenReturn(List.of(
                new VoiceChannelMembersDto(9L, List.of("carol"))));
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of());

        sweeper.sweep();

        verify(voiceAccess, never()).kickVideoPublishers(anyLong(), anySet());
    }
}
