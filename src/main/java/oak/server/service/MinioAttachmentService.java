package oak.server.service;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.config.MinioProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MinIO-backed signer. Builds presigned PUT (upload) and GET (download) URLs against the
 * {@code publicEndpoint}, and creates the bucket at startup if missing. Active only when
 * {@code oak.minio.enabled=true}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oak.minio", name = "enabled", havingValue = "true")
public class MinioAttachmentService implements AttachmentUrlSigner, AttachmentObjectStore {

    private static final Duration PUT_EXPIRY = Duration.ofMinutes(5);
    private static final Duration GET_EXPIRY = Duration.ofHours(1);

    private final MinioClient minioClient;
    private final MinioProperties properties;

    @PostConstruct
    void ensureBucket() {
        String bucket = properties.bucket();
        try {
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[MinIO] Created bucket '{}'", bucket);
            }
        } catch (Exception minioError) {
            throw new IllegalStateException(
                    "Could not verify/create MinIO bucket '" + bucket + "': " + minioError.getMessage(), minioError);
        }
    }

    @Override
    public String newKey(String fileName) {
        return key("attachments", fileName);
    }

    @Override
    public String newAvatarKey(String fileName) {
        return key("avatars", fileName);
    }

    @Override
    public String newOriginalKey(String fileName) {
        return key("avatars/originals", fileName);
    }

    @Override
    public String newServerAvatarKey(String fileName) {
        return key("server-avatars", fileName);
    }

    @Override
    public String newEmojiKey(String fileName) {
        return key("custom-emojis", fileName);
    }

    @Override
    public String newStickerKey(String fileName) {
        return key("stickers", fileName);
    }

    private static String key(String prefix, String fileName) {
        return prefix + "/" + UUID.randomUUID() + "/" + safeName(fileName);
    }

    @Override
    public String presignedPutUrl(String key) {
        return sign(Method.PUT, key, PUT_EXPIRY);
    }

    @Override
    public String presignedGetUrl(String key) {
        return sign(Method.GET, key, GET_EXPIRY);
    }

    @Override
    public void delete(String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(key)
                    .build());
        } catch (Exception minioError) {
            // Best-effort: a leftover orphan object is harmless, but log it so it's visible.
            log.warn("[MinIO] Could not delete object '{}': {}", key, minioError.getMessage());
        }
    }

    @Override
    public List<StoredObject> listAttachmentObjects() {
        // The recursive listing under the attachments/ prefix only — avatars have their own
        // lifecycle (replaced/removed with the profile) and are never swept.
        return listByPrefix("attachments/");
    }

    @Override
    public List<StoredObject> listCustomEmojiObjects() {
        // The emoji catalog's own listing: reclaimed rows are deleted by EmojiController, the
        // only orphan here is an abandoned upload slot (night sweep checks the emoji table).
        return listByPrefix("custom-emojis/");
    }

    @Override
    public List<StoredObject> listStickerObjects() {
        // The sticker catalog's listing — the same abandoned-slot reclaim story as emojis.
        return listByPrefix("stickers/");
    }

    private List<StoredObject> listByPrefix(String prefix) {
        List<StoredObject> objects = new ArrayList<>();
        Iterable<Result<Item>> listing = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket(properties.bucket())
                .prefix(prefix)
                .recursive(true)
                .build());
        for (Result<Item> entry : listing) {
            try {
                Item item = entry.get();
                objects.add(new StoredObject(item.objectName(), item.size(),
                        Instant.ofEpochMilli(item.lastModified().toInstant().toEpochMilli())));
            } catch (Exception minioError) {
                log.warn("[MinIO] Listing skipped one object: {}", minioError.getMessage());
            }
        }
        return objects;
    }

    private String sign(Method method, String key, Duration expiry) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                            .method(method)
                            .bucket(properties.bucket())
                            .object(key)
                            .expiry((int) expiry.getSeconds())
                            .build());
        } catch (Exception minioError) {
            throw new IllegalStateException("Could not sign MinIO URL for '" + key + "': " + minioError.getMessage(), minioError);
        }
    }

    private static String safeName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        String name = Paths.get(fileName).getFileName().toString(); // strip any path the client sent
        return name.isBlank() ? "file" : name;
    }
}
