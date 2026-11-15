package in.ilovemyfiles.file;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PhysicalFileRepository extends JpaRepository<PhysicalFileEntity, UUID> {

    /**
     * Dedup lookup, scoped to the uploading user. Locks the entry so a delete of
     * its object can't run in between (see StorageCleanupService).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PhysicalFileEntity> findByUserIdAndHash(UUID userId, String hash);

    /** Every dedup entry describing one stored object, locked. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PhysicalFileEntity p WHERE p.storageKey = :storageKey")
    List<PhysicalFileEntity> lockAllByStorageKey(@Param("storageKey") String storageKey);
}
