package oak.server.repository;

import oak.server.entity.StickerPack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StickerPackRepository extends JpaRepository<StickerPack, Long> {

    boolean existsByName(String name);

    List<StickerPack> findAllByOrderByNameAsc();
}
