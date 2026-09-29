package oak.server.repository;

import oak.server.entity.Sticker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StickerRepository extends JpaRepository<Sticker, Long> {

    /** The /link OR and the night sweep's stickers pass both reduce to this check. */
    boolean existsByFileKey(String fileKey);

    /** Per-pack uniqueness (names may repeat across packs). */
    boolean existsByPackIdAndName(Long packId, String name);

    /** Pack deletion collects the file keys before the rows go. */
    List<Sticker> findByPackId(Long packId);

    /** The catalog GET groups these under their packs in one pass. */
    List<Sticker> findAllByOrderByNameAsc();
}
