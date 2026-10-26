package in.ilovemyfiles.file;

import in.ilovemyfiles.analytics.ActivityService;
import in.ilovemyfiles.chunk.UploadSessionEntity;
import in.ilovemyfiles.chunk.UploadSessionRepository;
import in.ilovemyfiles.notification.NotificationService;
import in.ilovemyfiles.storage.StorageCapacityService;
import in.ilovemyfiles.user.User;
import in.ilovemyfiles.user.UserRepository;
import in.ilovemyfiles.user.UserService;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
@Transactional
class FileServiceIntegrationTest {

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
    @MockBean UserService userService;
    @MockBean ActivityService activityService;
    @MockBean NotificationService notificationService;

    @Autowired FileService fileService;
    @Autowired FileRepository fileRepository;
    @Autowired FileVersionRepository versionRepository;
    @Autowired UserRepository userRepository;
    @Autowired UploadSessionRepository sessionRepository;
    @Autowired StorageCapacityService storageCapacityService;

    // Instance field — new UUID per test instance
    private UUID userId;

    @BeforeEach
    void setUp() {
        // Let DB generate the UUID (em.persist not em.merge)
        User saved = userRepository.saveAndFlush(User.builder()
            .email("test-" + UUID.randomUUID() + "@example.com")
            .build());
        userId = saved.getId();

        User mockUser = mock(User.class);
        lenient().when(mockUser.getStorageUsed()).thenReturn(0L);
        lenient().when(mockUser.getStorageLimit()).thenReturn(5_368_709_120L);
        lenient().when(userService.findById(any())).thenReturn(mockUser);
    }

    @Test
    void newFile_persistsAndCreatesVersionOne() {
        FileDto result = fileService.createOrUpdateFile(
            "report.txt", 1024L, "text/plain", null, userId, "key/v1", null, false
        );

        assertNotNull(result.getId());
        assertEquals("report.txt", result.getName());
        assertEquals(1024L, result.getSize());

        FileEntity stored = fileRepository
            .findByIdAndUserIdAndIsDeletedFalse(result.getId(), userId)
            .orElseThrow();
        assertEquals("report.txt", stored.getName());
        assertEquals(1L, versionRepository.countByFileId(result.getId()));
    }

    @Test
    void sameFilenameUploadedTwice_addsVersion() {
        UUID secondUserId = createUser();

        FileDto v1 = fileService.createOrUpdateFile(
            "doc.txt", 512L, "text/plain", null, secondUserId, "key/v1", null, false
        );
        FileDto v2 = fileService.createOrUpdateFile(
            "doc.txt", 1024L, "text/plain", null, secondUserId, "key/v2", null, false
        );

        assertEquals(v1.getId(), v2.getId(), "Version upload keeps the same file ID");
        assertEquals(1024L, v2.getSize(), "File size updated to latest version");
        assertEquals(2L, versionRepository.countByFileId(v1.getId()), "Version count is 2");
    }

    @Test
    void deduplicated_doesNotUpdateStorage() {
        UUID secondUserId = createUser();

        fileService.createOrUpdateFile(
            "dup.jpg", 2048L, "image/jpeg", null, secondUserId, "key/shared", null, true
        );

        verify(userService, never()).updateStorageUsed(eq(secondUserId), anyLong());
    }

    @Test
    void newFile_callsUpdateStorageUsedWithFileSize() {
        fileService.createOrUpdateFile(
            "photo.jpg", 500L, "image/jpeg", null, userId, "key/photo", null, false
        );
        verify(userService).updateStorageUsed(userId, 500L);
    }

    @Test
    void storedBytes_countsTrashAndOlderVersionsButNotOtherUsers() {
        // 1000 B, then overwritten with 3000 B: both copies stay in storage.
        fileService.createOrUpdateFile("a.txt", 1000L, "text/plain", null, userId, "key/a1", null, false);
        fileService.createOrUpdateFile("a.txt", 3000L, "text/plain", null, userId, "key/a2", null, false);
        // 500 B moved to the trash: still stored for 30 days.
        FileDto trashed = fileService.createOrUpdateFile("b.txt", 500L, "text/plain", null, userId, "key/b1", null, false);
        FileEntity b = fileRepository.findById(trashed.getId()).orElseThrow();
        b.setIsDeleted(true);
        fileRepository.saveAndFlush(b);
        // Another account's file must not count.
        fileService.createOrUpdateFile("c.txt", 9999L, "text/plain", null, createUser(), "key/c1", null, false);
        fileRepository.flush();

        assertEquals(3000L + 1000L + 500L, userRepository.storedBytes(userId));
    }

    @Test
    void capacity_countsEachStoredObjectOnceAndRecentUploadsInProgress() {
        long before = storageCapacityService.usedBytes();
        UUID otherUser = createUser();

        // Overwritten file: both copies are stored (1000 + 3000).
        fileService.createOrUpdateFile("a.txt", 1000L, "text/plain", null, userId, "key/a1", null, false);
        fileService.createOrUpdateFile("a.txt", 3000L, "text/plain", null, userId, "key/a2", null, false);
        // One object shared by two users through dedup: counted once (700).
        fileService.createOrUpdateFile("s.txt", 700L, "text/plain", null, userId, "key/shared", null, false);
        fileService.createOrUpdateFile("copy.txt", 700L, "text/plain", null, otherUser, "key/shared", null, true);
        // Trashed file: still stored (200).
        FileDto trashed = fileService.createOrUpdateFile("t.txt", 200L, "text/plain", null, userId, "key/t1", null, false);
        FileEntity t = fileRepository.findById(trashed.getId()).orElseThrow();
        t.setIsDeleted(true);
        fileRepository.saveAndFlush(t);
        // Uploads: a recent one in progress reserves its size (500); one abandoned
        // two days ago and one already completed don't.
        sessionRepository.save(session(500L, "in_progress", LocalDateTime.now()));
        sessionRepository.save(session(9000L, "in_progress", LocalDateTime.now().minusDays(2)));
        sessionRepository.save(session(8000L, "completed", LocalDateTime.now()));
        sessionRepository.flush();
        fileRepository.flush();

        assertEquals(before + 1000L + 3000L + 700L + 200L + 500L, storageCapacityService.usedBytes());
        // The advisory lock runs inside the surrounding transaction.
        assertDoesNotThrow(() -> storageCapacityService.checkCapacity(1));
    }

    @Test
    void uploadSession_storesR2LengthUploadIds() {
        // Cloudflare R2 multipart upload ids are ~343 characters long.
        String r2UploadId = "A".repeat(343);
        UploadSessionEntity upload = session(1024L, "in_progress", LocalDateTime.now());
        upload.setS3UploadId(r2UploadId);
        UploadSessionEntity saved = sessionRepository.saveAndFlush(upload);

        assertEquals(r2UploadId, sessionRepository.findById(saved.getId()).orElseThrow().getS3UploadId());
    }

    private UploadSessionEntity session(long size, String status, LocalDateTime createdAt) {
        return UploadSessionEntity.builder()
            .userId(userId).fileName("upload.bin").fileSize(size).totalChunks(1)
            .contentType("application/octet-stream").storageKey("key/session-" + UUID.randomUUID())
            .status(status).createdAt(createdAt)
            .build();
    }

    private UUID createUser() {
        return userRepository.saveAndFlush(User.builder()
            .email("extra-" + UUID.randomUUID() + "@example.com")
            .build()).getId();
    }
}
