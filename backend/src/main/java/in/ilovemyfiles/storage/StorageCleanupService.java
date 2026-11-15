package in.ilovemyfiles.storage;

import in.ilovemyfiles.file.PhysicalFileEntity;
import in.ilovemyfiles.file.PhysicalFileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Deletes stored objects once nothing points at them.
 *
 * <p>Dedup lets several files, and older versions, share one object, so an
 * object may only go when no {@code files} row and no {@code file_versions} row
 * references its key any more. That is checked by query at delete time. The old
 * {@code ref_count} column drifted (folder deletes never decremented it, version
 * deletes ignored it) and let one user's delete remove bytes another user's file
 * still pointed at.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageCleanupService {

    private static final String REFERENCED_SQL = """
            SELECT EXISTS (SELECT 1 FROM files WHERE storage_key = ?)
                OR EXISTS (SELECT 1 FROM file_versions WHERE storage_key = ?)
            """;

    private final PhysicalFileRepository physicalFileRepository;
    private final StorageService storageService;
    private final JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Call inside the transaction that removed rows pointing at {@code storageKey},
     * after removing them. If no row references the object any more, its dedup
     * entry is removed now and the object itself is deleted once the transaction
     * commits; never before, so a rollback can't leave rows pointing at nothing.
     *
     * @return true if the object was scheduled for deletion
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deleteIfUnreferenced(String storageKey) {
        // Send this transaction's pending row deletes to the database, so the
        // reference check below sees them.
        entityManager.flush();

        // Lock the dedup entries for this object: an upload deduplicating onto
        // it concurrently either commits first (and the check sees its row) or
        // waits, then finds the entry gone and uploads normally.
        List<PhysicalFileEntity> entries = physicalFileRepository.lockAllByStorageKey(storageKey);

        Boolean referenced = jdbc.queryForObject(REFERENCED_SQL, Boolean.class, storageKey, storageKey);
        if (Boolean.TRUE.equals(referenced)) {
            return false;
        }
        physicalFileRepository.deleteAll(entries);
        deleteAfterCommit(storageKey);
        return true;
    }

    private void deleteAfterCommit(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storageService.deleteObject(storageKey);
                } catch (RuntimeException e) {
                    // The rows are already gone, so this only leaves an orphaned
                    // object behind; no file that is still in use is affected.
                    log.warn("Could not delete {} from storage: {}", storageKey, e.getMessage());
                }
            }
        });
    }
}
