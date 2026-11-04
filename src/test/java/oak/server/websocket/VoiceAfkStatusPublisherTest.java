package oak.server.websocket;

import oak.server.dto.VoiceAfkStatusEvent;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.entity.ServerProfile;
import oak.server.repository.ServerProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The roster AFK dimension (user request 2026-11-04): the SAME registry+timeout the room
 * sweep decides by, published as per-flip presence deltas — one frame per transition, no
 * repeats, and leaving voice clears the flag. Independent of the AFK channel setting.
 */
@ExtendWith(MockitoExtension.class)
class VoiceAfkStatusPublisherTest {

    @Mock
    private VoiceMembershipTracker tracker;

    @Mock
    private VoiceActivityRegistry registry;

    @Mock
    private ServerProfileRepository profileRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private VoiceAfkStatusPublisher publisher;

    /** The entity's no-arg constructor is protected — a mock feeds timeoutOf cleanly. */
    private ServerProfile profileWithTimeout(Integer minutes) {
        ServerProfile profile = mock(ServerProfile.class);
        when(profile.getAfkTimeoutMinutes()).thenReturn(minutes);
        return profile;
    }

    @Test
    void idleBeyondTheTimeoutFlipsOnceAndDoesNotRepeat() {
        ServerProfile profile = profileWithTimeout(5);
        when(profileRepository.singleton()).thenReturn(profile);
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(1L, List.of("alice"))));
        when(registry.lastActive("alice")).thenReturn(Instant.now().minus(Duration.ofMinutes(6)));

        publisher.publishAfkTransitions();
        publisher.publishAfkTransitions();

        verify(messagingTemplate, times(1))
                .convertAndSend(eq(VoiceAfkStatusPublisher.TOPIC), eq(new VoiceAfkStatusEvent("alice", true)));
    }

    @Test
    void aFreshHeartbeatReturnsTheDotToGreen() {
        ServerProfile profile = profileWithTimeout(5);
        when(profileRepository.singleton()).thenReturn(profile);
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(1L, List.of("alice"))));
        when(registry.lastActive("alice")).thenReturn(Instant.now().minus(Duration.ofMinutes(6)));

        publisher.publishAfkTransitions();

        when(registry.lastActive("alice")).thenReturn(Instant.now().minus(Duration.ofMinutes(1)));
        publisher.publishAfkTransitions();
        publisher.publishAfkTransitions();

        verify(messagingTemplate, times(1))
                .convertAndSend(eq(VoiceAfkStatusPublisher.TOPIC), eq(new VoiceAfkStatusEvent("alice", true)));
        verify(messagingTemplate, times(1))
                .convertAndSend(eq(VoiceAfkStatusPublisher.TOPIC), eq(new VoiceAfkStatusEvent("alice", false)));
    }

    @Test
    void leavingVoiceClearsTheFlag() {
        ServerProfile profile = profileWithTimeout(5);
        when(profileRepository.singleton()).thenReturn(profile);
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(1L, List.of("alice"))));
        when(registry.lastActive("alice")).thenReturn(Instant.now().minus(Duration.ofMinutes(6)));

        publisher.publishAfkTransitions();

        when(tracker.snapshot()).thenReturn(List.of());
        publisher.publishAfkTransitions();

        verify(messagingTemplate, times(1))
                .convertAndSend(eq(VoiceAfkStatusPublisher.TOPIC), eq(new VoiceAfkStatusEvent("alice", false)));
    }

    @Test
    void worksWithoutAnAfkChannelConfigured() {
        // A null timeout (and a null channel — not even read here) falls back to the
        // default 30 minutes: the roster indicator does not depend on the sweep setup.
        ServerProfile profile = profileWithTimeout(null);
        when(profileRepository.singleton()).thenReturn(profile);
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(1L, List.of("alice"))));
        when(registry.lastActive("alice")).thenReturn(Instant.now().minus(Duration.ofMinutes(31)));

        publisher.publishAfkTransitions();

        verify(messagingTemplate, times(1))
                .convertAndSend(eq(VoiceAfkStatusPublisher.TOPIC), eq(new VoiceAfkStatusEvent("alice", true)));
    }

    @Test
    void anActiveMemberNeverFlips() {
        ServerProfile profile = profileWithTimeout(5);
        when(profileRepository.singleton()).thenReturn(profile);
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(1L, List.of("alice"))));
        when(registry.lastActive("alice")).thenReturn(Instant.now());

        publisher.publishAfkTransitions();

        verify(messagingTemplate, never()).convertAndSend(eq(VoiceAfkStatusPublisher.TOPIC), any(VoiceAfkStatusEvent.class));
    }
}
