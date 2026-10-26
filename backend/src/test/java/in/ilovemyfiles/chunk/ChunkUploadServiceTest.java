package in.ilovemyfiles.chunk;

import in.ilovemyfiles.analytics.ActivityService;
import in.ilovemyfiles.common.BadRequestException;
import in.ilovemyfiles.common.StorageQuotaException;
import in.ilovemyfiles.file.FileService;
import in.ilovemyfiles.file.PhysicalFileRepository;
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

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChunkUploadServiceTest {

    private static final long MB = 1024L * 1024;

    @Mock UploadSessionRepository sessionRepository;
    @Mock FileService fileService;
    @Mock PhysicalFileRepository physicalFileRepository;
    @Mock UserService userService;
    @Mock ActivityService activityService;
    @Mock NotificationService notificationService;
    @Mock StorageService storageService;
    @Mock StorageCapacityService storageCapacityService;

    @InjectMocks ChunkUploadService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setLimit() {
        ReflectionTestUtils.setField(service, "maxUploadSize", DataSize.ofMegabytes(75));
    }

    private static InitUploadRequest request(long size, int totalChunks) {
        return new InitUploadRequest("lecture.mp4", size, totalChunks, "video/mp4", null, "a1b2c3");
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
                .when(userService).checkQuota(user, 10 * MB);

        assertThrows(StorageQuotaException.class, () -> service.init(request(10 * MB, 2), userId));

        // Checked before dedup, so a duplicate can't slip past a full account either.
        verifyNoInteractions(physicalFileRepository, storageService, sessionRepository);
    }

    @Test
    void rejectsEveryUploadOnceTheAppWideCeilingIsReached() {
        when(userService.findById(userId)).thenReturn(new User());
        doThrow(new StorageQuotaException("Uploads are paused because the service's storage is full."))
                .when(storageCapacityService).checkCapacity(MB);

        assertThrows(StorageQuotaException.class, () -> service.init(request(MB, 1), userId));

        // Not even a duplicate of already-stored content gets through.
        verifyNoInteractions(physicalFileRepository, storageService, sessionRepository);
    }

    @Test
    void acceptsAFileExactlyAtTheLimit() {
        User user = new User();
        when(physicalFileRepository.findByHash("a1b2c3")).thenReturn(Optional.empty());
        when(userService.findById(userId)).thenReturn(user);
        when(storageService.createMultipartUpload(anyString(), eq("video/mp4"))).thenReturn("s3-upload-id");
        when(storageService.presignUploadPart(anyString(), eq("s3-upload-id"), anyInt())).thenReturn("https://part-url");
        when(sessionRepository.saveAndFlush(any())).thenAnswer(inv -> {
            UploadSessionEntity session = inv.getArgument(0);
            session.setId(UUID.randomUUID());
            return session;
        });

        InitUploadResponse response = service.init(request(75 * MB, 15), userId);

        verify(userService).checkQuota(user, 75 * MB);
        verify(storageCapacityService).checkCapacity(75 * MB);
        assertFalse(response.isDuplicate());
        assertEquals(15, response.getParts().size());
    }

    @Test
    void abortsTheStorageUploadWhenTheSessionCannotBeSaved() {
        when(userService.findById(userId)).thenReturn(new User());
        when(physicalFileRepository.findByHash("a1b2c3")).thenReturn(Optional.empty());
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
}
