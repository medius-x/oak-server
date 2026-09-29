package oak.server.service;

import oak.server.entity.Message;
import oak.server.entity.ServerProfile;
import oak.server.repository.EmojiRepository;
import oak.server.repository.MessageReactionRepository;
import oak.server.repository.MessageRepository;
import oak.server.repository.ServerProfileRepository;
import oak.server.repository.StickerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The watermark-tier decision loop: below the high watermark nothing is purged; above it the
 * oldest batches go until the low watermark; the min-keep floor and the no-progress detector
 * (the Postgres bloat signature) each stop the loop before it eats history for nothing. The
 * disk percent is injected through the test seam — the real filesystem cannot be scripted.
 */
@ExtendWith(MockitoExtension.class)
class StorageGcServiceTest {

    private static final int HIGH = 85;
    private static final int LOW = 50;

    @Mock
    private ServerProfileRepository profileRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageReactionRepository reactionRepository;
    @Mock
    private MessageService messageService;
    @Mock
    private AttachmentObjectStore objectStore;
    @Mock
    private EmojiRepository emojiRepository;
    @Mock
    private StickerRepository stickerRepository;

    private ServerProfile profile;
    private StorageGcService service;

    @BeforeEach
    void setUp() {
        profile = new ServerProfile("OAK");
        profile.setStorageHighWatermarkPercent(HIGH);
        profile.setStorageLowWatermarkPercent(LOW);
        lenient().when(profileRepository.singleton()).thenReturn(profile);
        // The emoji/sticker passes of the orphan sweep (lenient: not every test reaches them).
        lenient().when(objectStore.listCustomEmojiObjects()).thenReturn(List.of());
        lenient().when(objectStore.listStickerObjects()).thenReturn(List.of());
        // The batch purge runs inside a TransactionTemplate — in unit tests the callback just runs.
        TransactionTemplate template = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        service = new StorageGcService(profileRepository, messageRepository, reactionRepository,
                messageService, objectStore, emojiRepository, stickerRepository, realCodec(), template,
                null /* DataSource unused: vacuum is skipped — H2/not reached in unit tests */,
                ".", true, 1000, 7, Duration.ofMinutes(10));
    }

    private static AttachmentListCodec realCodec() {
        return new AttachmentListCodec(tools.jackson.databind.json.JsonMapper.builder().build());
    }

    @Test
    void belowHighWatermarkPurgesNothing() {
        when(objectStore.listAttachmentObjects()).thenReturn(List.of());

        StorageGcService.StorageGcReport report =
                service.runOnce("test", () -> 40.0);

        assertTrue(report.outcome.contains("below high watermark"));
        assertEquals(0, report.purgedMessages);
        verify(messageRepository, never()).findIdsOldestFirst(any(Pageable.class));
    }

    @Test
    void purgesOldestBatchesUntilLowWatermarkAndKillsOrphanedObjects() {
        when(objectStore.listAttachmentObjects()).thenReturn(List.of());
        when(messageRepository.count()).thenReturn(10_000L);
        // Three batches of 500 available; the disk goal lands after two.
        when(messageRepository.findIdsOldestFirst(any(Pageable.class)))
                .thenReturn(ids(1), ids(2), ids(3))
                .thenReturn(List.of());
        when(messageRepository.findAllById(anyCollection()))
                .thenAnswer(invocation -> ((List<Long>) invocation.getArgument(0))
                        .stream().map(id -> messageWithAttachment(id, "att/keep-" + id % 2)).toList());
        // Even ids' key keeps a live reference (a forward), odd ids' key is orphaned by the batch.
        when(messageService.attachmentExists(anyString())).thenAnswer(
                invocation -> ((String) invocation.getArgument(0)).contains("keep-1"));
        // The disk supplier is called twice before the loop (log line + pre-loop measure),
        // then once per completed batch: 90, 90 → batch1 → 70 → batch2 → 45 (below LOW).
        AtomicInteger call = new AtomicInteger();
        double[] percents = {90.0, 90.0, 70.0, 45.0};
        java.util.function.DoubleSupplier disk = () -> percents[Math.min(call.getAndIncrement(), percents.length - 1)];

        StorageGcService.StorageGcReport report = service.runOnce("test", disk);

        assertEquals("low watermark reached", report.outcome);
        assertEquals(1000, report.purgedMessages); // 2 batches × 500
        // Per batch the keys dedup into {keep-0, keep-1}; only keep-0 dies → 1 object × 2 batches.
        assertEquals(2, report.purgedAttachmentObjects);
        verify(objectStore, org.mockito.Mockito.times(2)).delete("att/keep-0");
        verify(objectStore, never()).delete("att/keep-1");
    }

    @Test
    void noProgressStopsTheLoopInsteadOfEatingHistory() {
        when(objectStore.listAttachmentObjects()).thenReturn(List.of());
        when(messageRepository.count()).thenReturn(1_000_000L);
        when(messageRepository.findIdsOldestFirst(any(Pageable.class))).thenReturn(ids(1));
        when(messageRepository.findAllById(anyCollection()))
                .thenAnswer(invocation -> ((List<Long>) invocation.getArgument(0))
                        .stream().map(id -> messageWithAttachment(id, "att/x-" + id)).toList());
        when(messageService.attachmentExists(anyString())).thenReturn(false);
        // The percent NEVER moves — the Postgres bloat signature.
        StorageGcService.StorageGcReport report = service.runOnce("test", () -> 95.0);

        assertTrue(report.outcome.contains("does not free disk space"));
        assertEquals(3 * 500, report.purgedMessages); // NO_PROGRESS_BATCHES batches, then the stop
    }

    @Test
    void minKeepFloorProtectsTheNewestHistory() {
        when(objectStore.listAttachmentObjects()).thenReturn(List.of());
        when(messageRepository.count()).thenReturn(900L); // at/below the 1000-message floor

        StorageGcService.StorageGcReport report = service.runOnce("test", () -> 95.0);

        assertTrue(report.outcome.contains("min-keep floor"));
        verify(messageRepository, never()).findIdsOldestFirst(any(Pageable.class));
    }

    @Test
    void scheduledTickNeverPurgesWhenAutomaticCleanupIsDisabled() {
        profile.setStorageGcEnabled(false);

        service.scheduledTick();

        org.mockito.Mockito.verifyNoInteractions(messageRepository, objectStore);
        assertEquals(null, service.lastReport());
    }

    @Test
    void sweepDeletesOnlyOldUnreferencedObjects() {
        Instant now = Instant.now();
        when(objectStore.listAttachmentObjects()).thenReturn(List.of(
                new AttachmentObjectStore.StoredObject("att/young", 10, now),                 // in grace
                new AttachmentObjectStore.StoredObject("att/referenced", 20, now.minus(Duration.ofDays(30))),
                new AttachmentObjectStore.StoredObject("att/orphan", 30, now.minus(Duration.ofDays(30)))));
        when(messageService.attachmentExists("att/referenced")).thenReturn(true);
        when(messageService.attachmentExists("att/orphan")).thenReturn(false);
        // Below the high watermark — the sweep still ran, the purge did not.
        StorageGcService.StorageGcReport report = service.runOnce("test", () -> 40.0);

        assertEquals(1, report.sweptOrphanObjects);
        assertEquals(30, report.sweptBytes);
        verify(objectStore).delete("att/orphan");
        verify(objectStore, never()).delete("att/young");
    }

    @Test
    void emojiSweepReclaimsAbandonedSlotsAndKeepsCatalogedOnes() {
        Instant now = Instant.now();
        when(objectStore.listAttachmentObjects()).thenReturn(List.of());
        when(objectStore.listCustomEmojiObjects()).thenReturn(List.of(
                // In grace — an upload still in its slot→PUT→create flight.
                new AttachmentObjectStore.StoredObject("custom-emojis/u1/fresh.png", 10, now),
                // Cataloged — a live emoji row holds the key.
                new AttachmentObjectStore.StoredObject("custom-emojis/u1/live.png", 20, now.minus(Duration.ofDays(30))),
                // Abandoned — slot minted, row never created, grace long over.
                new AttachmentObjectStore.StoredObject("custom-emojis/u1/abandoned.png", 40, now.minus(Duration.ofDays(30)))));
        when(emojiRepository.existsByFileKey("custom-emojis/u1/live.png")).thenReturn(true);
        when(emojiRepository.existsByFileKey("custom-emojis/u1/abandoned.png")).thenReturn(false);
        StorageGcService.StorageGcReport report = service.runOnce("test", () -> 40.0);

        assertEquals(1, report.sweptOrphanObjects);
        assertEquals(40, report.sweptBytes);
        verify(objectStore).delete("custom-emojis/u1/abandoned.png");
        verify(objectStore, never()).delete("custom-emojis/u1/live.png");
        verify(objectStore, never()).delete("custom-emojis/u1/fresh.png");
    }

    @Test
    void stickerSweepReclaimsAbandonedSlotsAndKeepsCatalogedOnes() {
        Instant now = Instant.now();
        when(objectStore.listAttachmentObjects()).thenReturn(List.of());
        when(objectStore.listStickerObjects()).thenReturn(List.of(
                new AttachmentObjectStore.StoredObject("stickers/u1/fresh.png", 10, now),
                new AttachmentObjectStore.StoredObject("stickers/u1/live.png", 20, now.minus(Duration.ofDays(30))),
                new AttachmentObjectStore.StoredObject("stickers/u1/abandoned.png", 40, now.minus(Duration.ofDays(30)))));
        when(stickerRepository.existsByFileKey("stickers/u1/live.png")).thenReturn(true);
        when(stickerRepository.existsByFileKey("stickers/u1/abandoned.png")).thenReturn(false);
        StorageGcService.StorageGcReport report = service.runOnce("test", () -> 40.0);

        assertEquals(1, report.sweptOrphanObjects);
        assertEquals(40, report.sweptBytes);
        verify(objectStore).delete("stickers/u1/abandoned.png");
        verify(objectStore, never()).delete("stickers/u1/live.png");
        verify(objectStore, never()).delete("stickers/u1/fresh.png");
    }

    private static List<Long> ids(int batchNumber) {
        return java.util.stream.LongStream.rangeClosed(batchNumber * 500L, batchNumber * 500L + 499L)
                .boxed().toList();
    }

    private static Message messageWithAttachment(Long id, String key) {
        Message message = new Message(null, null, "m");
        message.setId(id);
        message.setAttachments(realCodec().write(List.of(
                new oak.server.dto.AttachmentRef(key, "file.png", null, null))));
        return message;
    }
}
