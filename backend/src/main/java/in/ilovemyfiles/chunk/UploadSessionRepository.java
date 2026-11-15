package in.ilovemyfiles.chunk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UploadSessionRepository extends JpaRepository<UploadSessionEntity, UUID> {

    Optional<UploadSessionEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<UploadSessionEntity> findByIdAndUserIdAndStatus(UUID id, UUID userId, String status);

    /**
     * Bytes this user's unfinished uploads hold. Same one-day window as the
     * app-wide reservation in StorageCapacityService (after a day the bucket's
     * lifecycle rule aborts them).
     */
    @Query(value = """
            SELECT CAST(COALESCE(SUM(file_size), 0) AS BIGINT) FROM upload_sessions
            WHERE user_id = :userId AND status = 'in_progress'
              AND created_at > NOW() - INTERVAL '1 day'
            """, nativeQuery = true)
    long reservedBytes(@Param("userId") UUID userId);

    /** How many unfinished uploads this user has open (same window). */
    @Query(value = """
            SELECT COUNT(*) FROM upload_sessions
            WHERE user_id = :userId AND status = 'in_progress'
              AND created_at > NOW() - INTERVAL '1 day'
            """, nativeQuery = true)
    long countActive(@Param("userId") UUID userId);

    // Earlier attempts of the same upload: same content, name and folder.
    List<UploadSessionEntity> findByUserIdAndStatusAndHashAndFileNameAndFolderId(
            UUID userId, String status, String hash, String fileName, UUID folderId);

    List<UploadSessionEntity> findByUserIdAndStatusAndHashAndFileNameAndFolderIdIsNull(
            UUID userId, String status, String hash, String fileName);
}
