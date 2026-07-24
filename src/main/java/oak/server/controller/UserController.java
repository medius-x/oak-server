package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AttachmentUploadRequest;
import oak.server.dto.AttachmentUploadResponse;
import oak.server.dto.AvatarKeysRequest;
import oak.server.dto.AvatarOriginalResponse;
import oak.server.dto.MyPermissions;
import oak.server.dto.UserResponse;
import oak.server.dto.UserSummary;
import oak.server.entity.Permissions;
import oak.server.entity.User;
import oak.server.repository.UserRepository;
import oak.server.service.AttachmentUrlSigner;
import oak.server.service.AuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * User lookups — the roster (list) and the current-user profile — plus avatar management (a
 * two-phase upload: mint MinIO slots, then confirm the keys after the PUTs). All endpoints require
 * a valid JWT (only {@code /api/auth/**} is public).
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final AttachmentUrlSigner attachmentUrlSigner;
    private final SimpMessagingTemplate messagingTemplate;
    private final AuthorizationService auth;

    /** STOMP topic a live user update (e.g. an avatar change) is pushed to: {@code /topic/users}. */
    public static final String USERS_TOPIC = "/topic/users";

    /**
     * All users of this instance as a compact roster. Returns {@link UserSummary} with each user's
     * avatar URL; the full profile stays on {@code /api/users/me}.
     */
    @GetMapping
    public List<UserSummary> list() {
        return userRepository.findAllByOrderByUsernameAsc().stream()
                .map(user -> UserSummary.from(user, attachmentUrlSigner))
                .toList();
    }

    @GetMapping("/me")
    public UserResponse currentUser(@AuthenticationPrincipal UserDetails principal) {
        return userRepository.findByUsername(principal.getUsername())
                .map(user -> UserResponse.from(user, attachmentUrlSigner))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /**
     * The caller's effective server-wide permissions (names) + OWNER flag — the client gates its UI
     * from this (hide the channel "+" without {@code MANAGE_CHANNELS}, etc.). Channel-scoped checks
     * are resolved server-side at the action.
     */
    @GetMapping("/me/permissions")
    public MyPermissions myPermissions(@AuthenticationPrincipal UserDetails principal) {
        User user = requireUser(principal);
        return new MyPermissions(Permissions.names(auth.effectiveBase(user)), auth.isOwner(user));
    }

    /**
     * Mints a MinIO upload slot for a new avatar (the cropped 256px image). The client uploads the
     * bytes directly to MinIO, then confirms both keys via {@code /me/avatar/confirm}. Only image
     * extensions are accepted — the server never sees the bytes, so this name check is the only
     * server-side type gate (the client also filters by extension in its picker).
     */
    @PostMapping("/me/avatar")
    public AttachmentUploadResponse requestAvatarUpload(@AuthenticationPrincipal UserDetails principal,
                                                        @RequestBody @Valid AttachmentUploadRequest request) {
        requireImageExtension(request.fileName());
        String key = attachmentUrlSigner.newAvatarKey(request.fileName());
        return new AttachmentUploadResponse(key, attachmentUrlSigner.presignedPutUrl(key));
    }

    /** Mints a MinIO upload slot for the original (pre-crop) avatar image (kept for re-cropping). */
    @PostMapping("/me/avatar/original")
    public AttachmentUploadResponse requestOriginalUpload(@AuthenticationPrincipal UserDetails principal,
                                                          @RequestBody @Valid AttachmentUploadRequest request) {
        requireImageExtension(request.fileName());
        String key = attachmentUrlSigner.newOriginalKey(request.fileName());
        return new AttachmentUploadResponse(key, attachmentUrlSigner.presignedPutUrl(key));
    }

    /**
     * Binds freshly-uploaded avatar objects to the current user, called by the client after the MinIO
     * PUTs succeed (so a failed upload never leaves dangling keys). {@code originalKey} is optional —
     * null on a re-crop keeps the existing original.
     */
    @PostMapping("/me/avatar/confirm")
    @Transactional
    public UserResponse confirmAvatar(@AuthenticationPrincipal UserDetails principal,
                                      @RequestBody @Valid AvatarKeysRequest request) {
        User user = requireUser(principal);
        String previousAvatarKey = user.getAvatarKey();
        user.setAvatarKey(request.avatarKey());
        String previousOriginalKey = null;
        if (request.originalKey() != null) {
            previousOriginalKey = user.getAvatarOriginalKey();
            user.setAvatarOriginalKey(request.originalKey());
        }
        userRepository.save(user);
        deleteIfReplaced(previousAvatarKey, request.avatarKey());
        deleteIfReplaced(previousOriginalKey, request.originalKey());
        publishUserUpdate(user);
        return UserResponse.from(user, attachmentUrlSigner);
    }

    /** Clears the current user's avatar (both keys) and deletes the old MinIO objects. */
    @DeleteMapping("/me/avatar")
    @Transactional
    public UserResponse removeAvatar(@AuthenticationPrincipal UserDetails principal) {
        User user = requireUser(principal);
        String previousAvatarKey = user.getAvatarKey();
        String previousOriginalKey = user.getAvatarOriginalKey();
        user.setAvatarKey(null);
        user.setAvatarOriginalKey(null);
        userRepository.save(user);
        deleteIfReplaced(previousAvatarKey, null);
        deleteIfReplaced(previousOriginalKey, null);
        publishUserUpdate(user);
        return UserResponse.from(user, attachmentUrlSigner);
    }

    /**
     * Lazy: the pre-crop original for the re-crop editor. Returns a freshly-signed presigned GET URL,
     * or 404 when the user has no original (e.g. avatar set before this feature existed).
     */
    @GetMapping("/me/avatar/original")
    public AvatarOriginalResponse originalAvatarUrl(@AuthenticationPrincipal UserDetails principal) {
        User user = requireUser(principal);
        String originalKey = user.getAvatarOriginalKey();
        if (originalKey == null || originalKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No avatar original");
        }
        return new AvatarOriginalResponse(attachmentUrlSigner.presignedGetUrl(originalKey));
    }

    /** Deletes the previous object from MinIO when the key actually changed (replace/remove). */
    private void deleteIfReplaced(String previousKey, String newKey) {
        if (previousKey != null && !previousKey.equals(newKey)) {
            attachmentUrlSigner.delete(previousKey);
        }
    }

    /** Broadcasts a compact user view so connected clients repaint the avatar/roster live. */
    private void publishUserUpdate(User user) {
        messagingTemplate.convertAndSend(USERS_TOPIC, UserSummary.from(user, attachmentUrlSigner));
    }

    private User requireUser(UserDetails principal) {
        return userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static void requireImageExtension(String fileName) {
        if (fileName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File name is required");
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Avatar must be a PNG or JPEG image");
        }
    }
}
