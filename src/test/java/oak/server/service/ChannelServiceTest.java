package oak.server.service;

import oak.server.dto.ChannelResponse;
import oak.server.dto.CreateChannelRequest;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.repository.ChannelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito tests for the channel-creation push wiring: creating a channel persists it and
 * broadcasts it on the STOMP channels topic, so every connected client adds it to its sidebar
 * live (the same REST-write → push pattern as {@code MessageService}).
 */
@ExtendWith(MockitoExtension.class)
class ChannelServiceTest {

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ChannelService channelService;

    @Test
    void createPersistsChannelAndBroadcastsItToChannelsTopic() {
        Channel saved = new Channel("general", ChannelType.TEXT);
        saved.setId(42L);
        when(channelRepository.save(any(Channel.class))).thenReturn(saved);

        ChannelResponse response = channelService.create(new CreateChannelRequest("general", ChannelType.TEXT));

        assertEquals(42L, response.id());
        assertEquals("general", response.name());
        assertEquals(ChannelType.TEXT, response.type());
        verify(messagingTemplate).convertAndSend(eq(ChannelService.CHANNELS_TOPIC), any(ChannelResponse.class));
    }

    @Test
    void createDefaultsTypeToTextAndBroadcastsIt() {
        Channel saved = new Channel("lounge", ChannelType.TEXT);
        saved.setId(7L);
        when(channelRepository.save(any(Channel.class))).thenReturn(saved);

        channelService.create(new CreateChannelRequest("lounge", null));

        ArgumentCaptor<ChannelResponse> captor = ArgumentCaptor.forClass(ChannelResponse.class);
        verify(messagingTemplate).convertAndSend(eq(ChannelService.CHANNELS_TOPIC), captor.capture());
        assertEquals(ChannelType.TEXT, captor.getValue().type());
        assertEquals("lounge", captor.getValue().name());
    }
}
