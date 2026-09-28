package oak.server.repository;

import oak.server.entity.CustomEmoji;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmojiRepository extends JpaRepository<CustomEmoji, Long> {

    boolean existsByName(String name);

    /** The /attachments/link existence OR and the night orphan sweep both resolve
     *  emoji rows by their MinIO key. */
    boolean existsByFileKey(String fileKey);

    List<CustomEmoji> findAllByOrderByNameAsc();
}
