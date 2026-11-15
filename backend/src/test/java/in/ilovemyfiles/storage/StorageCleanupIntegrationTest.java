package in.ilovemyfiles.storage;

import in.ilovemyfiles.analytics.ActivityService;
import in.ilovemyfiles.file.FileDto;
import in.ilovemyfiles.file.FileRepository;
import in.ilovemyfiles.file.FileService;
import in.ilovemyfiles.file.FileVersionEntity;
import in.ilovemyfiles.file.FileVersionRepository;
import in.ilovemyfiles.file.FileVersionService;
import in.ilovemyfiles.file.PhysicalFileRepository;
import in.ilovemyfiles.notification.NotificationService;
import in.ilovemyfiles.scheduler.SchedulerService;
import in.ilovemyfiles.trash.TrashService;
import in.ilovemyfiles.user.User;
import in.ilovemyfiles.user.UserRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Object deletion against a real database. Nothing here runs inside a test
 * transaction: objects are deleted only after a commit, so every step has to
 * commit for real to observe (or rule out) a delete.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "minio.endpoint=http://localhost:9000",
        "minio.access-key=test-access",
        "minio.secret-key=test-secret",
        "minio.bucket=test-bucket",
        "app.share-base-url=http://localhost:5173",
        "cors.allowed-origins=http://localhost:5173",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
    }
)
@Testcontainers
class StorageCleanupIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean MinioClient minioClient;
    @MockBean RedisConnectionFactory redisConnectionFactory;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean StorageService storageService;
    @MockBean ActivityService activityService;
    @MockBean NotificationService notificationService;

    @Autowired FileService fileService;
    @Autowired FileVersionService fileVersionService;
    @Autowired TrashService trashService;
    @Autowired SchedulerService schedulerService;
    @Autowired FileRepository fileRepository;
    @Autowired FileVersionRepository versionRepository;
    @Autowired PhysicalFileRepository physicalFileRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate tx;

    private UUID newUser() {
        return userRepository.save(User.builder().email("u-" + UUID.randomUUID() + "@example.com").build()).getId();
    }

    private static String key() {
        return "k/" + UUID.randomUUID();
    }

    private FileDto store(UUID userId, String name, String storageKey, boolean deduplicated) {
        return fileService.createOrUpdateFile(name, 100L, "text/plain", null, userId, storageKey, null, deduplicated);
    }

    private FileVersionEntity version(UUID fileId, int number) {
        return versionRepository.findByFileIdOrderByVersionDesc(fileId).stream()
                .filter(v -> v.getVersion() == number).findFirst().orElseThrow();
    }

    @Test
    void deletingAnOldVersion_keepsBytesAnotherUsersFileStillUses() {
        UUID alice = newUser();
        UUID bob = newUser();
        String shared = key();
        FileDto report = store(alice, "report.pdf", shared, false);
        FileDto copy = store(bob, "copy.pdf", shared, true);   // cross-user dedup from before V15
        store(bob, "copy.pdf", key(), false);                   // bob's v2

        fileVersionService.deleteVersion(copy.getId(), version(copy.getId(), 1).getId(), bob);

        // This used to remove the object outright, breaking alice's file.
        verify(storageService, never()).deleteObject(shared);
        assertEquals(shared, fileRepository.findById(report.getId()).orElseThrow().getStorageKey());
    }

    @Test
    void deletingTheLastReference_deletesTheObjectAndItsDedupEntry() {
        UUID carol = newUser();
        String first = key();
        FileDto solo = fileService.finalizeUpload("solo.txt", 100L, "text/plain", null, carol, first, "1".repeat(64));
        store(carol, "solo.txt", key(), false);

        fileVersionService.deleteVersion(solo.getId(), version(solo.getId(), 1).getId(), carol);

        verify(storageService).deleteObject(first);
        assertTrue(physicalFileRepository.findAll().stream().noneMatch(p -> p.getStorageKey().equals(first)));
    }

    @Test
    void aRolledBackDelete_neverTouchesStorage() {
        UUID dave = newUser();
        String first = key();
        FileDto draft = store(dave, "draft.txt", first, false);
        store(dave, "draft.txt", key(), false);
        UUID v1 = version(draft.getId(), 1).getId();

        tx.executeWithoutResult(status -> {
            fileVersionService.deleteVersion(draft.getId(), v1, dave);
            status.setRollbackOnly();
        });

        verify(storageService, never()).deleteObject(first);
        assertTrue(versionRepository.findById(v1).isPresent());
    }

    @Test
    void permanentDelete_removesEveryUnsharedVersionObject_andKeepsSharedOnes() {
        UUID erin = newUser();
        String k1 = key();
        String k2 = key();
        String k3 = key();
        FileDto doc = store(erin, "doc.txt", k1, false);
        store(erin, "doc.txt", k2, false);
        store(erin, "doc.txt", k3, false);
        store(erin, "other.txt", k2, true);   // shares doc's v2 bytes through dedup

        fileService.delete(doc.getId(), erin);
        trashService.hardDelete(doc.getId(), erin);

        // Old versions' objects used to be left behind forever.
        verify(storageService).deleteObject(k1);
        verify(storageService).deleteObject(k3);
        verify(storageService, never()).deleteObject(k2);
        assertTrue(versionRepository.findByFileIdOrderByVersionDesc(doc.getId()).isEmpty());
    }

    @Test
    void versionPruning_removesOnlyVersionsPastTheRetentionLimit() {
        UUID frank = newUser();
        String[] keys = new String[12];
        UUID fileId = null;
        for (int i = 0; i < keys.length; i++) {
            keys[i] = key();
            FileDto stored = store(frank, "log.txt", keys[i], false);
            if (fileId == null) fileId = stored.getId();
        }

        schedulerService.runVersionCleanup();   // keeps the newest 10

        verify(storageService).deleteObject(keys[0]);
        verify(storageService).deleteObject(keys[1]);
        verify(storageService, never()).deleteObject(keys[2]);
        assertEquals(10, versionRepository.findByFileIdOrderByVersionDesc(fileId).size());
    }
}
