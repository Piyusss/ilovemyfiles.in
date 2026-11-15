package in.ilovemyfiles.chunk;

import in.ilovemyfiles.analytics.ActivityService;
import in.ilovemyfiles.common.BadRequestException;
import in.ilovemyfiles.common.NotFoundException;
import in.ilovemyfiles.common.StorageQuotaException;
import in.ilovemyfiles.common.TooManyRequestsException;
import in.ilovemyfiles.file.FileDto;
import in.ilovemyfiles.file.FileService;
import in.ilovemyfiles.file.PhysicalFileEntity;
import in.ilovemyfiles.file.PhysicalFileRepository;
import in.ilovemyfiles.folder.FolderRepository;
import in.ilovemyfiles.notification.NotificationService;
import in.ilovemyfiles.storage.StorageCapacityService;
import in.ilovemyfiles.storage.StorageService;
import in.ilovemyfiles.user.User;
import in.ilovemyfiles.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChunkUploadServiceTest {

    private static final long MB = 1024L * 1024;
    private static final String HASH = "a".repeat(64);

    @Mock UploadSessionRepository sessionRepository;
    @Mock FileService fileService;
    @Mock PhysicalFileRepository physicalFileRepository;
    @Mock UserService userService;
    @Mock ActivityService activityService;
    @Mock NotificationService notificationService;
    @Mock StorageService storageService;
    @Mock StorageCapacityService storageCapacityService;
    @Mock FolderRepository folderRepository;

    @InjectMocks ChunkUploadService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setLimit() {
        ReflectionTestUtils.setField(service, "maxUploadSize", DataSize.ofMegabytes(75));
    }

    private static InitUploadRequest request(long size, int totalChunks) {
        return new InitUploadRequest("lecture.mp4", size, totalChunks, "video/mp4", null, HASH);
    }

    private PhysicalFileEntity ownCopy(long size) {
        return PhysicalFileEntity.builder()
                .id(UUID.randomUUID()).userId(userId).hash(HASH)
                .size(size).storageKey(userId + "/existing").build();
    }

    private void storageAcceptsANewUpload() {
        when(storageService.createMultipartUpload(anyString(), eq("video/mp4"))).thenReturn("s3-upload-id");
        when(storageService.presignUploadPart(anyString(), eq("s3-upload-id"), anyInt())).thenReturn("https://part-url");
        when(sessionRepository.saveAndFlush(any())).thenAnswer(inv -> {
            UploadSessionEntity session = inv.getArgument(0);
            session.setId(UUID.randomUUID());
            return session;
        });
    }

    @Test
    void rejectsAFileOverTheLimitBeforeDedupOrStorage() {
        var ex = assertThrows(BadRequestException.class,
                () -> service.init(request(75 * MB + 1, 16), userId));

        assertEquals("Files larger than 75 MB can't be uploaded", ex.getMessage());
        // Even content that's already stored (dedup) must not get past the limit.
        verifyNoInteractions(physicalFileRepository, storageService, sessionRepository);
    }

    @Test
    void rejectsAnUploadThatWouldExceedTheAccountQuota() {
        User user = new User();
        when(userService.findById(userId)).thenReturn(user);
        doThrow(new StorageQuotaException("Not enough space: 2 MB of your 75 MB is free"))
                .when(userService).checkQuota(user, 10 * MB, 0L);

        assertThrows(StorageQuotaException.class, () -> service.init(request(10 * MB, 2), userId));

        // Checked before dedup, so a duplicate can't slip past a full account either.
        verifyNoInteractions(physicalFileRepository, storageService);
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsEveryUploadOnceTheAppWideCeilingIsReached() {
        when(userService.findById(userId)).thenReturn(new User());
        doThrow(new StorageQuotaException("Uploads are paused because the service's storage is full."))
                .when(storageCapacityService).checkCapacity(MB);

        assertThrows(StorageQuotaException.class, () -> service.init(request(MB, 1), userId));

        // Not even a duplicate of already-stored content gets through.
        verifyNoInteractions(physicalFileRepository, storageService);
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void acceptsAFileExactlyAtTheLimit() {
        User user = new User();
        when(userService.findById(userId)).thenReturn(user);
        storageAcceptsANewUpload();

        InitUploadResponse response = service.init(request(75 * MB, 15), userId);

        verify(userService).checkQuota(user, 75 * MB, 0L);
        verify(storageCapacityService).checkCapacity(75 * MB);
        assertFalse(response.isDuplicate());
        assertEquals(15, response.getParts().size());
    }

    @Test
    void abortsTheStorageUploadWhenTheSessionCannotBeSaved() {
        when(userService.findById(userId)).thenReturn(new User());
        when(storageService.createMultipartUpload(anyString(), eq("video/mp4"))).thenReturn("s3-upload-id");
        when(sessionRepository.saveAndFlush(any())).thenThrow(new RuntimeException("value too long"));

        assertThrows(RuntimeException.class, () -> service.init(request(MB, 1), userId));

        // Nothing is left dangling in storage.
        verify(storageService).abortMultipartUpload(anyString(), eq("s3-upload-id"));
        verify(storageService, never()).presignUploadPart(anyString(), anyString(), anyInt());
    }

    @Test
    void rejectsMorePartsThanTheDeclaredSizeCouldNeed() {
        // 10 MB needs at most 2 parts of 5 MB; asking for 100 presigned URLs is refused.
        assertThrows(BadRequestException.class, () -> service.init(request(10 * MB, 100), userId));

        verifyNoInteractions(physicalFileRepository, storageService, sessionRepository);
    }

    @Test
    void refusesToUploadIntoAFolderTheUserDoesNotOwn() {
        UUID someoneElsesFolder = UUID.randomUUID();
        when(folderRepository.findByIdAndUserId(someoneElsesFolder, userId)).thenReturn(Optional.empty());
        var req = new InitUploadRequest("x.bin", MB, 1, "video/mp4", someoneElsesFolder, HASH);

        assertThrows(NotFoundException.class, () -> service.init(req, userId));
        verifyNoInteractions(physicalFileRepository, storageService, sessionRepository);
    }

    // ── Unfinished uploads ───────────────────────────────────────────────────

    @Test
    void countsTheUsersUnfinishedUploadsAgainstTheirQuota() {
        User user = new User();
        when(userService.findById(userId)).thenReturn(user);
        when(sessionRepository.reservedBytes(userId)).thenReturn(70 * MB);
        doThrow(new StorageQuotaException("Not enough space"))
                .when(userService).checkQuota(user, 10 * MB, 70 * MB);

        // Unfinished uploads used to count for nothing: one account could reserve
        // the whole app-wide capacity and block everyone's uploads.
        assertThrows(StorageQuotaException.class, () -> service.init(request(10 * MB, 2), userId));
        verifyNoInteractions(storageService);
    }

    @Test
    void refusesAnotherUploadWhenTooManyAreUnfinished() {
        when(sessionRepository.countActive(userId)).thenReturn((long) ChunkUploadService.MAX_ACTIVE_UPLOADS);

        assertThrows(TooManyRequestsException.class, () -> service.init(request(MB, 1), userId));
        verifyNoInteractions(userService, storageService);
    }

    @Test
    void startingTheSameUploadAgainReplacesTheUnfinishedAttempt() {
        UploadSessionEntity abandoned = UploadSessionEntity.builder()
                .id(UUID.randomUUID()).userId(userId).fileName("lecture.mp4").fileSize(MB)
                .totalChunks(1).contentType("video/mp4").hash(HASH)
                .storageKey(userId + "/old").s3UploadId("old-upload").build();
        when(sessionRepository.findByUserIdAndStatusAndHashAndFileNameAndFolderIdIsNull(
                userId, "in_progress", HASH, "lecture.mp4")).thenReturn(List.of(abandoned));
        when(userService.findById(userId)).thenReturn(new User());
        storageAcceptsANewUpload();

        service.init(request(MB, 1), userId);

        verify(sessionRepository).deleteAll(List.of(abandoned));
        // No transaction in a unit test, so the abort runs at once (after commit in the app).
        verify(storageService).abortMultipartUpload(userId + "/old", "old-upload");
    }

    // ── Dedup ────────────────────────────────────────────────────────────────

    @Test
    void dedupsAgainstTheUploadersOwnStoredCopy() {
        when(userService.findById(userId)).thenReturn(new User());
        PhysicalFileEntity own = ownCopy(MB);
        when(physicalFileRepository.findByUserIdAndHash(userId, HASH)).thenReturn(Optional.of(own));
        when(storageService.exists(own.getStorageKey())).thenReturn(true);
        FileDto created = FileDto.builder().id(UUID.randomUUID()).build();
        when(fileService.createOrUpdateFile("lecture.mp4", MB, "video/mp4", null, userId,
                own.getStorageKey(), own.getId(), true)).thenReturn(created);

        InitUploadResponse response = service.init(request(MB, 1), userId);

        assertTrue(response.isDuplicate());
        assertSame(created, response.getFileItem());
        verify(storageService, never()).createMultipartUpload(anyString(), anyString());
    }

    @Test
    void uploadsNormallyWhenTheClaimedSizeDoesNotMatchTheStoredCopy() {
        when(userService.findById(userId)).thenReturn(new User());
        when(physicalFileRepository.findByUserIdAndHash(userId, HASH)).thenReturn(Optional.of(ownCopy(2 * MB)));
        storageAcceptsANewUpload();

        InitUploadResponse response = service.init(request(MB, 1), userId);

        // A hash with a different size is a wrong claim: no dedup, a real upload.
        assertFalse(response.isDuplicate());
        verify(fileService, never()).createOrUpdateFile(any(), anyLong(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void dropsAnEntryWhoseObjectIsGoneAndUploadsNormally() {
        when(userService.findById(userId)).thenReturn(new User());
        PhysicalFileEntity stale = ownCopy(MB);
        when(physicalFileRepository.findByUserIdAndHash(userId, HASH)).thenReturn(Optional.of(stale));
        when(storageService.exists(stale.getStorageKey())).thenReturn(false);
        storageAcceptsANewUpload();

        InitUploadResponse response = service.init(request(MB, 1), userId);

        assertFalse(response.isDuplicate());
        verify(physicalFileRepository).delete(stale);
    }
}
