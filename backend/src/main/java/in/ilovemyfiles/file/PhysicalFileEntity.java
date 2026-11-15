package in.ilovemyfiles.file;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A dedup entry: "this user already has the content with this SHA-256 stored
 * under this key". Entries are per user, because the hash comes from the
 * client and can't be verified (see V15__per_user_dedup.sql).
 */
@Entity
@Table(name = "physical_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhysicalFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Owner of the entry; dedup never matches across users. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 64)
    private String hash;

    @Column(nullable = false)
    private Long size;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
