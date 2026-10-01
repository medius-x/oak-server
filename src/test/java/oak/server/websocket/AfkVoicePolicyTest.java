package oak.server.websocket;

import oak.server.entity.ServerProfile;
import oak.server.repository.ServerProfileRepository;
import oak.server.service.JanusVoiceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of the AFK room's channel-wide suppression: entering the configured
 * AFK channel server-mutes the member (the MODERATOR layer — a self-unmute cannot lift
 * it), entering any other room (or entering anything with the feature off) touches
 * nothing.
 */
@ExtendWith(MockitoExtension.class)
class AfkVoicePolicyTest {

    @Mock
    private ServerProfileRepository profileRepository;
    @Mock
    private VoiceMembershipTracker tracker;
    @Mock
    private JanusVoiceAccessService voiceAccess;

    @InjectMocks
    private AfkVoicePolicy policy;

    @Test
    void enteringTheAfkChannelServerMutesTheMember() {
        ServerProfile profile = new ServerProfile("srv");
        profile.setAfkChannelId(77L);
        when(profileRepository.singleton()).thenReturn(profile);

        policy.applyOnEnter("alice", 77L);

        verify(tracker).setMuted(77L, "alice", true);
        verify(voiceAccess).moderateAudio(77L, "alice", true);
    }

    @Test
    void enteringAnyOtherChannelTouchesNothing() {
        ServerProfile profile = new ServerProfile("srv");
        profile.setAfkChannelId(77L);
        when(profileRepository.singleton()).thenReturn(profile);

        policy.applyOnEnter("alice", 7L);

        verifyNoInteractions(tracker);
        verify(voiceAccess, never()).moderateAudio(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void withoutAConfiguredAfkChannelNothingHappens() {
        when(profileRepository.singleton()).thenReturn(new ServerProfile("srv"));

        policy.applyOnEnter("alice", 77L);

        verifyNoInteractions(tracker, voiceAccess);
    }
}
