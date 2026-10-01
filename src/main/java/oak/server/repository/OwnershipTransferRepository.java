package oak.server.repository;

import oak.server.entity.OwnershipTransfer;
import oak.server.entity.OwnershipTransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** JPA repository for {@link OwnershipTransfer} (single-tenant: at most one PENDING exists). */
@Repository
public interface OwnershipTransferRepository extends JpaRepository<OwnershipTransfer, Long> {

    /** The single live PENDING offer, if any (created first wins — harmless: the writer
     *  cancels any existing PENDING before inserting a new one). */
    Optional<OwnershipTransfer> findFirstByStatusOrderByCreatedAtAsc(OwnershipTransferStatus status);
}
