package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.AttachmentUploadRequest;
import oak.server.dto.AttachmentUploadResponse;
import oak.server.dto.AvatarKeyRequest;
import oak.server.dto.UserResponse;
import oak.server.dto.UserSummary;
import oak.server.entity.User;
import oak.server.repository.UserRepository;
import oak.server.service.AttachmentUrlSigner;
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
 * two-phase upload: mint a MinIO slot, then confirm the key after the PUT). All endpoints require
 * a valid JWT (only {@code /api/auth/**} is public).
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final AttachmentUrlSigner attachmentUrlSigner;
    private final SimpMessagingTemplate messagingTemplate;

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
     * Mints a MinIO upload slot for a new avatar: object key + short-lived presigned PUT URL. The
     * client uploads the bytes directly to MinIO, then calls {@code /me/avatar/confirm} with the key
     * to bind it to the account. Only image extensions are accepted — the server never sees the
     * bytes, so this name check is the only server-side type gate (the client also filters by
     * extension in its file picker).
     */
    @PostMapping("/me/avatar")
    public AttachmentUploadResponse requestAvatarUpload(@AuthenticationPrincipal UserDetails principal,
                                                        @RequestBody @Valid AttachmentUploadRequest request) {
        requireImageExtension(request.fileName());
        String key = attachmentUrlSigner.newAvatarKey(request.fileName());
        return new AttachmentUploadResponse(key, attachmentUrlSigner.presignedPutUrl(key));
    }

    /**
     * Binds a freshly-uploaded avatar object to the current user. Called by the client after the
     * MinIO PUT succeeds, so a failed upload never leaves a dangling key on the account.
     */
    @PostMapping("/me/avatar/confirm")
    @Transactional
    public UserResponse confirmAvatar(@AuthenticationPrincipal UserDetails principal,
                                      @RequestBody @Valid AvatarKeyRequest request) {
        User user = requireUser(principal);
        String previousKey = user.getAvatarKey();
        user.setAvatarKey(request.key());
        userRepository.save(user);
        deleteIfReplaced(previousKey, request.key());
        publishUserUpdate(user);
        return UserResponse.from(user, attachmentUrlSigner);
    }

    /** Clears the current user's avatar and deletes the old MinIO object. */
    @DeleteMapping("/me/avatar")
    @Transactional
    public UserResponse removeAvatar(@AuthenticationPrincipal UserDetails principal) {
        User user = requireUser(principal);
        String previousKey = user.getAvatarKey();
        user.setAvatarKey(null);
        userRepository.save(user);
        deleteIfReplaced(previousKey, null);
        publishUserUpdate(user);
        return UserResponse.from(user, attachmentUrlSigner);
    }

    /** Deletes the previous avatar object from MinIO when the key actually changed (replace/remove). */
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
