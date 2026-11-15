package in.ilovemyfiles.file;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileRepository extends JpaRepository<FileEntity, UUID> {

    List<FileEntity> findByUserIdAndFolderIdIsNullAndIsDeletedFalseOrderByCreatedAtDesc(UUID userId);

    List<FileEntity> findByUserIdAndFolderIdAndIsDeletedFalseOrderByCreatedAtDesc(UUID userId, UUID folderId);

    List<FileEntity> findByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(UUID userId);

    Optional<FileEntity> findByIdAndUserIdAndIsDeletedFalse(UUID id, UUID userId);

    /** Same lookup, row locked: version restore and version delete serialize on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FileEntity> findLockedByIdAndUserIdAndIsDeletedFalse(UUID id, UUID userId);

    long countByUserIdAndIsDeletedFalse(UUID userId);

    long countByFolderIdAndIsDeletedFalse(UUID folderId);

    List<FileEntity> findByFolderIdAndIsDeletedFalse(UUID folderId);

    // For version detection: find existing file by name in same folder. The row
    // is locked so concurrent uploads of one file are numbered one after another.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FileEntity> findByUserIdAndNameAndFolderIdIsNullAndIsDeletedFalse(UUID userId, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FileEntity> findByUserIdAndNameAndFolderIdAndIsDeletedFalse(UUID userId, String name, UUID folderId);

    // Trash
    List<FileEntity> findByUserIdAndIsDeletedTrueOrderByDeletedAtDesc(UUID userId);

    Optional<FileEntity> findByIdAndUserIdAndIsDeletedTrue(UUID id, UUID userId);

    List<FileEntity> findByIsDeletedTrueAndDeletedAtBefore(LocalDateTime cutoff);
}
