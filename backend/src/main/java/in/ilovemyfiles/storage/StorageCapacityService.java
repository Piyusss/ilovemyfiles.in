package in.ilovemyfiles.storage;

import in.ilovemyfiles.common.StorageQuotaException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * App-wide storage ceiling, so the bucket never grows past the storage
 * provider's free tier. Once it is reached nobody can upload until files are
 * deleted. Deleting is always allowed.
 */
@Service
@RequiredArgsConstructor
public class StorageCapacityService {

    // Any constant works; it only has to be the same for every caller.
    private static final long CAPACITY_LOCK_KEY = 7_104_201L;

    // Each stored object once, even when dedup shares it between files or users:
    // live files, trashed files and older versions.
    private static final String STORED_BYTES_SQL = """
            SELECT CAST(COALESCE(SUM(size), 0) AS BIGINT) FROM (
                SELECT storage_key, MAX(size) AS size
                FROM (SELECT storage_key, size FROM files
                      UNION ALL
                      SELECT storage_key, size FROM file_versions) AS objects
                GROUP BY storage_key
            ) AS stored
            """;

    // Uploads that have started but not completed reserve their size. After a
    // day they count as abandoned (the bucket's lifecycle rule aborts them).
    private static final String IN_FLIGHT_BYTES_SQL = """
            SELECT CAST(COALESCE(SUM(file_size), 0) AS BIGINT) FROM upload_sessions
            WHERE status = 'in_progress' AND created_at > NOW() - INTERVAL '1 day'
            """;

    private final JdbcTemplate jdbc;

    @Value("${app.max-total-storage:9GB}")
    private DataSize maxTotalStorage;

    /** Bytes stored plus bytes reserved by uploads in progress. */
    public long usedBytes() {
        Long stored = jdbc.queryForObject(STORED_BYTES_SQL, Long.class);
        Long inFlight = jdbc.queryForObject(IN_FLIGHT_BYTES_SQL, Long.class);
        return (stored == null ? 0 : stored) + (inFlight == null ? 0 : inFlight);
    }

    /**
     * Throws when storing {@code extraBytes} more would pass the ceiling.
     * Call inside the transaction that records the upload: the advisory lock
     * makes check-then-insert atomic across concurrent requests and backend
     * instances, and is released when that transaction ends.
     */
    public void checkCapacity(long extraBytes) {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + CAPACITY_LOCK_KEY + ")");
        if (usedBytes() + extraBytes > maxTotalStorage.toBytes()) {
            throw new StorageQuotaException(
                    "Uploads are paused because the service's storage is full. Deleting files frees space.");
        }
    }
}
