package oak.server.websocket;

import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import oak.server.service.JanusVoiceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of the SPEAK-permission gate on room entry (security round 6): entering a
 * channel without SPEAK writes the derived permission-mute (tracker + forced gateway layer),
 * entering with the right touches nothing — the mark died with the previous leave, so there
 * is no lift branch on the entry path.
 */
@ExtendWith(MockitoExtension.class)
class SpeakVoicePolicyTest {

    @Mock
    private AuthorizationService auth;
    @Mock
    private VoiceMembershipTracker tracker;
    @Mock
    private JanusVoiceAccessService voiceAccess;

    @InjectMocks
    private SpeakVoicePolicy policy;

    @Test
    void enteringWithoutSpeakPermMutesTheMember() {
        Channel stage = voiceChannel(7L);
        User listener = user("alice");
        when(auth.hasInChannel(listener, stage, Permission.SPEAK)).thenReturn(false);

        policy.applyOnEnter(listener, stage);

        verify(tracker).setPermMuted(7L, "alice", true);
        verify(voiceAccess).moderateAudio(7L, "alice", true);
    }

    @Test
    void enteringWithSpeakTouchesNothing() {
        Channel lounge = voiceChannel(7L);
        User speaker = user("alice");
        when(auth.hasInChannel(speaker, lounge, Permission.SPEAK)).thenReturn(true);

        policy.applyOnEnter(speaker, lounge);

        verifyNoInteractions(tracker);
        verifyNoInteractions(voiceAccess);
    }

    // --- fixtures ---

    private static Channel voiceChannel(Long id) {
        Channel channel = new Channel("stage", ChannelType.VOICE);
        channel.setId(id);
        return channel;
    }

    private static User user(String username) {
        return new User(username, "hash");
    }
}
