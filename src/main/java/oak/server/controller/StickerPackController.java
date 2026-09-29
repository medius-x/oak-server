package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.CreateStickerPackRequest;
import oak.server.dto.StickerPackResponse;
import oak.server.entity.Permission;
import oak.server.entity.Sticker;
import oak.server.entity.StickerPack;
import oak.server.entity.User;
import oak.server.repository.StickerPackRepository;
import oak.server.repository.StickerRepository;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The sticker-pack half of the custom-sticker catalog (phase 2). The whole catalog — packs
 * with their stickers nested — is one fetch open to any authenticated member (render data,
 * the {@code GET /api/server} stance); every mutation broadcasts a ping on
 * {@value #STICKER_TOPIC} and clients refetch. Sticker management deliberately rides the
 * EXISTING {@link Permission#MANAGE_EMOJIS} (one gate for the whole "expressions" catalog —
 * the Discord MANAGE_EXPRESSIONS collapse, without a new enum bit).
 */
@RestController
@RequestMapping("/api/sticker-packs")
@RequiredArgsConstructor
public class StickerPackController {

    /** STOMP topic pinged (a bare Long timestamp) after every sticker/pack mutation. */
    public static final String STICKER_TOPIC = "/topic/stickers";

    /** Hard cap on pack count. */
    static final int MAX_STICKER_PACKS = 20;

    private final StickerPackRepository packRepository;
    private final StickerRepository stickerRepository;
    private final AttachmentUrlSigner attachmentUrlSigner;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;

    /** The whole catalog (packs name-ordered, stickers name-ordered within each). Two
     *  queries + an in-memory group-by — packs never N+1. */
    @GetMapping
    public List<StickerPackResponse> list() {
        Map<Long, List<Sticker>> stickersByPack = stickerRepository.findAllByOrderByNameAsc().stream()
                .collect(Collectors.groupingBy(sticker -> sticker.getPack().getId()));
        return packRepository.findAllByOrderByNameAsc().stream()
                .map(pack -> StickerPackResponse.from(pack,
                        stickersByPack.getOrDefault(pack.getId(), List.of()), attachmentUrlSigner))
                .toList();
    }

    /** Creates a pack ({@code MANAGE_EMOJIS}); the name is free text, unique. */
    @PostMapping
    @Transactional
    public StickerPackResponse create(@AuthenticationPrincipal UserDetails principal,
                                      @RequestBody @Valid CreateStickerPackRequest request) {
        requireManageStickers(principal);
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pack name must not be blank");
        }
        if (packRepository.existsByName(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pack name already taken");
        }
        if (packRepository.count() >= MAX_STICKER_PACKS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Sticker pack limit reached (" + MAX_STICKER_PACKS + ")");
        }
        StickerPack pack = new StickerPack(name);
        try {
            pack = packRepository.save(pack);
        } catch (DataIntegrityViolationException race) {
            // The existsByName pre-check lost a concurrent create for the same name.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pack name already taken");
        }
        publishPing();
        return StickerPackResponse.from(pack, List.of(), attachmentUrlSigner);
    }

    /** Removes the pack AND its stickers (rows first — the FK has no cascade), then the
     *  objects best-effort. Messages carrying those stickers degrade to tombstones. */
    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        requireManageStickers(principal);
        StickerPack pack = packRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown sticker pack"));
        List<Sticker> stickers = stickerRepository.findByPackId(id);
        stickerRepository.deleteAll(stickers);
        packRepository.delete(pack);
        for (Sticker sticker : stickers) {
            attachmentUrlSigner.delete(sticker.getFileKey()); // best-effort — a dead object is not fatal
        }
        publishPing();
    }

    private User requireManageStickers(UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireServer(actor, Permission.MANAGE_EMOJIS);
        return actor;
    }

    private void publishPing() {
        messagingTemplate.convertAndSend(STICKER_TOPIC, System.currentTimeMillis());
    }
}
