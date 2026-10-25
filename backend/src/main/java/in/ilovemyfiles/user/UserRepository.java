package in.ilovemyfiles.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByClerkId(String clerkId);

    /**
     * Bytes the account keeps in storage: every file including trashed ones,
     * plus older versions whose stored copy differs from the file's current one.
     */
    @Query(value = """
            SELECT CAST(
                COALESCE((SELECT SUM(f.size) FROM files f WHERE f.user_id = :userId), 0)
              + COALESCE((SELECT SUM(fv.size) FROM file_versions fv
                          JOIN files f ON f.id = fv.file_id
                          WHERE f.user_id = :userId AND fv.storage_key <> f.storage_key), 0)
            AS BIGINT)
            """, nativeQuery = true)
    long storedBytes(@Param("userId") UUID userId);
}
