package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AttachmentUploadRequest;
import oak.server.dto.AttachmentUploadResponse;
import oak.server.dto.CreateEmojiRequest;
import oak.server.dto.EmojiResponse;
import oak.server.entity.CustomEmoji;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.EmojiRepository;
import oak.server.service.AttachmentUrlSigner;
import oak.server.service.AuthorizationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * The server-scoped custom-emoji catalog (phase 1: a flat list; packs land with stickers in
 * phase 2). Messages reference emojis only as {@code :name:} plain text — the server never
 * touches {@code MessageResponse}; clients resolve tokens against this catalog and refetch
 * it on the {@value #EMOJI_TOPIC} ping (any mutation broadcasts one).
 *
 * <p>Upload is two-phase (the avatar discipline): {@code POST /slot} mints a
 * {@code custom-emojis/} key + presigned PUT, the client uploads the bytes, then
 * {@code POST /api/emojis} creates the row. A slot whose row never lands is an orphan the
 * night sweep reclaims (it checks this table). All mutations gate on
 * {@link Permission#MANAGE_EMOJIS}.
 */
@RestController
@RequestMapping("/api/emojis")
@RequiredArgsConstructor
public class EmojiController {

    /** STOMP topic pinged (a bare Long timestamp) after every catalog mutation. */
    public static final String EMOJI_TOPIC = "/topic/emojis";

    /** Hard cap on the catalog (Discord's default server tier). */
    static final int MAX_EMOJIS = 200;

    private static final String EMOJI_KEY_PREFIX = "custom-emojis/";

    private final EmojiRepository emojiRepository;
    private final AttachmentUrlSigner attachmentUrlSigner;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;

    /** The whole catalog (name-ordered). Open to any authenticated member — it is render
     *  data, exactly like {@code GET /api/server}. */
    @GetMapping
    public List<EmojiResponse> list() {
        return emojiRepository.findAllByOrderByNameAsc().stream()
                .map(emoji -> EmojiResponse.from(emoji, attachmentUrlSigner))
                .toList();
    }

    /** Phase 1 of the upload: mint a {@code custom-emojis/} slot ({@code MANAGE_EMOJIS}). */
    @PostMapping("/slot")
    public AttachmentUploadResponse requestUpload(@AuthenticationPrincipal UserDetails principal,
                                                  @RequestBody @Valid AttachmentUploadRequest request) {
        requireManageEmojis(principal);
        requireImageExtension(request.fileName());
        String key = attachmentUrlSigner.newEmojiKey(request.fileName());
        return new AttachmentUploadResponse(key, attachmentUrlSigner.presignedPutUrl(key));
    }

    /** Phase 2: create the catalog row for an uploaded key ({@code MANAGE_EMOJIS}). */
    @PostMapping
    @Transactional
    public EmojiResponse create(@AuthenticationPrincipal UserDetails principal,
                                @RequestBody @Valid CreateEmojiRequest request) {
        User actor = requireManageEmojis(principal);
        if (emojiRepository.existsByName(request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Emoji name already taken");
        }
        if (emojiRepository.count() >= MAX_EMOJIS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Emoji limit reached (" + MAX_EMOJIS + ")");
        }
        if (!request.fileKey().startsWith(EMOJI_KEY_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown upload slot");
        }
        CustomEmoji emoji = new CustomEmoji(request.name(), request.fileKey(), actor);
        try {
            emoji = emojiRepository.save(emoji);
        } catch (DataIntegrityViolationException race) {
            // The existsByName pre-check lost a concurrent create for the same name.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Emoji name already taken");
        }
        return publish(emoji);
    }

    /** Removes the row and its object; messages using {@code :name:} degrade to plain text
     *  (no FK exists — the Discord tradeoff). {@code MANAGE_EMOJIS}. */
    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        requireManageEmojis(principal);
        CustomEmoji emoji = emojiRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown emoji"));
        String fileKey = emoji.getFileKey();
        emojiRepository.delete(emoji);
        attachmentUrlSigner.delete(fileKey); // best-effort — a dead object is not fatal
        publishPing();
    }

    private User requireManageEmojis(UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireServer(actor, Permission.MANAGE_EMOJIS);
        return actor;
    }

    private static void requireImageExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".gif") && !lower.endsWith(".webp")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Emojis must be PNG, GIF or WebP images");
        }
    }

    private EmojiResponse publish(CustomEmoji emoji) {
        publishPing();
        return EmojiResponse.from(emoji, attachmentUrlSigner);
    }

    private void publishPing() {
        messagingTemplate.convertAndSend(EMOJI_TOPIC, System.currentTimeMillis());
    }
}
