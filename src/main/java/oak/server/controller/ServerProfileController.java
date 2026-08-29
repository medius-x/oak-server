package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AttachmentUploadRequest;
import oak.server.dto.AttachmentUploadResponse;
import oak.server.dto.AvatarKeysRequest;
import oak.server.dto.AvatarOriginalResponse;
import oak.server.dto.ServerProfileResponse;
import oak.server.dto.UpdateConnectionInfoRequest;
import oak.server.dto.UpdateServerNameRequest;
import oak.server.entity.Permission;
import oak.server.entity.ServerProfile;
import oak.server.entity.User;
import oak.server.repository.ServerProfileRepository;
import oak.server.service.AttachmentUrlSigner;
import oak.server.service.AuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * The instance identity: its stored {@code name} and icon. {@code GET /api/server} is open to any
 * authenticated member (every client fetches it on connect to label the sidebar); every mutation —
 * rename and the two-phase icon upload (slot → PUT → confirm → remove) — is gated by
 * {@code MANAGE_SERVER}. Each mutation pushes the fresh profile to {@value #SERVER_TOPIC} so every
 * connected client repaints its sidebar icon + name live. Mirrors {@code UserController}'s avatar flow.
 */
@RestController
@RequestMapping("/api/server")
@RequiredArgsConstructor
public class ServerProfileController {

    /** STOMP topic a live server-profile update (name/icon change) is pushed to. */
    public static final String SERVER_TOPIC = "/topic/server";

    private final ServerProfileRepository serverProfileRepository;
    private final AttachmentUrlSigner attachmentUrlSigner;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;

    /** The instance name + icon. Open to any authenticated member. */
    @GetMapping
    public ServerProfileResponse get() {
        return ServerProfileResponse.from(profile(), attachmentUrlSigner);
    }

    /** Renames the instance ({@code MANAGE_SERVER}); pushes the new profile to {@value #SERVER_TOPIC}. */
    @PatchMapping
    @Transactional
    public ServerProfileResponse rename(@AuthenticationPrincipal UserDetails principal,
                                        @RequestBody @Valid UpdateServerNameRequest request) {
        requireManageServer(principal);
        ServerProfile profile = profile();
        profile.setName(request.name().trim());
        serverProfileRepository.save(profile);
        return publish(profile);
    }

    /**
     * Sets the advertised client-connection address (host + port — what a member types into
     * "Add server"). {@code MANAGE_SERVER}; null fields leave the stored part unchanged. The write
     * path is the future remote-provisioning flow (the setup client pushes the address here) and the
     * manual fill for rows seeded before the fields existed. Separate segment from the name
     * {@code PATCH /api/server} so neither request shape grows optional-field ambiguity.
     */
    @PatchMapping("/connection")
    @Transactional
    public ServerProfileResponse updateConnection(@AuthenticationPrincipal UserDetails principal,
                                                  @RequestBody @Valid UpdateConnectionInfoRequest request) {
        requireManageServer(principal);
        if (request.connectionHost() != null && request.connectionHost().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Connection host must not be blank");
        }
        ServerProfile profile = profile();
        if (request.connectionHost() != null) {
            profile.setConnectionHost(request.connectionHost().trim());
        }
        if (request.connectionPort() != null) {
            profile.setConnectionPort(request.connectionPort());
        }
        serverProfileRepository.save(profile);
        return publish(profile);
    }

    /** Mints a MinIO upload slot for a new server icon (the cropped 256px image). {@code MANAGE_SERVER}. */
    @PostMapping("/avatar")
    public AttachmentUploadResponse requestAvatarUpload(@AuthenticationPrincipal UserDetails principal,
                                                        @RequestBody @Valid AttachmentUploadRequest request) {
        requireManageServer(principal);
        requireImageExtension(request.fileName());
        String key = attachmentUrlSigner.newServerAvatarKey(request.fileName());
        return new AttachmentUploadResponse(key, attachmentUrlSigner.presignedPutUrl(key));
    }

    /** Mints a MinIO upload slot for the original (pre-crop) icon image (kept for re-cropping). {@code MANAGE_SERVER}. */
    @PostMapping("/avatar/original")
    public AttachmentUploadResponse requestOriginalUpload(@AuthenticationPrincipal UserDetails principal,
                                                          @RequestBody @Valid AttachmentUploadRequest request) {
        requireManageServer(principal);
        requireImageExtension(request.fileName());
        String key = attachmentUrlSigner.newOriginalKey(request.fileName());
        return new AttachmentUploadResponse(key, attachmentUrlSigner.presignedPutUrl(key));
    }

    /**
     * Binds freshly-uploaded icon objects to the instance, called by the client after the MinIO PUTs
     * succeed (so a failed upload never leaves dangling keys). {@code originalKey} is optional — null
     * on a re-crop keeps the existing original. {@code MANAGE_SERVER}.
     */
    @PostMapping("/avatar/confirm")
    @Transactional
    public ServerProfileResponse confirmAvatar(@AuthenticationPrincipal UserDetails principal,
                                               @RequestBody @Valid AvatarKeysRequest request) {
        requireManageServer(principal);
        ServerProfile profile = profile();
        String previousAvatarKey = profile.getAvatarKey();
        profile.setAvatarKey(request.avatarKey());
        String previousOriginalKey = null;
        if (request.originalKey() != null) {
            previousOriginalKey = profile.getAvatarOriginalKey();
            profile.setAvatarOriginalKey(request.originalKey());
        }
        serverProfileRepository.save(profile);
        deleteIfReplaced(previousAvatarKey, request.avatarKey());
        deleteIfReplaced(previousOriginalKey, request.originalKey());
        return publish(profile);
    }

    /** Clears the instance icon (both keys) and deletes the old MinIO objects. {@code MANAGE_SERVER}. */
    @DeleteMapping("/avatar")
    @Transactional
    public ServerProfileResponse removeAvatar(@AuthenticationPrincipal UserDetails principal) {
        requireManageServer(principal);
        ServerProfile profile = profile();
        String previousAvatarKey = profile.getAvatarKey();
        String previousOriginalKey = profile.getAvatarOriginalKey();
        profile.setAvatarKey(null);
        profile.setAvatarOriginalKey(null);
        serverProfileRepository.save(profile);
        deleteIfReplaced(previousAvatarKey, null);
        deleteIfReplaced(previousOriginalKey, null);
        return publish(profile);
    }

    /** Lazy: the pre-crop original for the re-crop editor ({@code MANAGE_SERVER}); 404 when none is set. */
    @GetMapping("/avatar/original")
    public AvatarOriginalResponse originalAvatarUrl(@AuthenticationPrincipal UserDetails principal) {
        requireManageServer(principal);
        String originalKey = profile().getAvatarOriginalKey();
        if (originalKey == null || originalKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No server icon original");
        }
        return new AvatarOriginalResponse(attachmentUrlSigner.presignedGetUrl(originalKey));
    }

    private ServerProfile profile() {
        return serverProfileRepository.singleton();
    }

    private void requireManageServer(UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireServer(actor, Permission.MANAGE_SERVER);
    }

    /** Broadcasts the profile so connected clients repaint the sidebar icon + name live. */
    private ServerProfileResponse publish(ServerProfile profile) {
        ServerProfileResponse response = ServerProfileResponse.from(profile, attachmentUrlSigner);
        messagingTemplate.convertAndSend(SERVER_TOPIC, response);
        return response;
    }

    private void deleteIfReplaced(String previousKey, String newKey) {
        if (previousKey != null && !previousKey.equals(newKey)) {
            attachmentUrlSigner.delete(previousKey);
        }
    }

    private static void requireImageExtension(String fileName) {
        if (fileName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File name is required");
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Icon must be a PNG or JPEG image");
        }
    }
}
