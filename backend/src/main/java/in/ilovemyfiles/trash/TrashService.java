package in.ilovemyfiles.trash;

import in.ilovemyfiles.analytics.ActivityService;
import in.ilovemyfiles.common.NotFoundException;
import in.ilovemyfiles.file.FileEntity;
import in.ilovemyfiles.file.FileRepository;
import in.ilovemyfiles.file.FileService;
import in.ilovemyfiles.file.FileVersionEntity;
import in.ilovemyfiles.file.FileVersionRepository;
import in.ilovemyfiles.folder.FolderRepository;
import in.ilovemyfiles.storage.StorageCleanupService;
import in.ilovemyfiles.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrashService {

    private final FileRepository fileRepository;
    private final FileVersionRepository versionRepository;
    private final FolderRepository folderRepository;
    private final UserService userService;
    private final ActivityService activityService;
    private final FileService fileService;
    private final StorageCleanupService storageCleanupService;

    private static final int RETENTION_DAYS = 30;

    // ── List trash ────────────────────────────────────────────────────────────

    public List<TrashItemDto> list(UUID userId) {
        List<FileEntity> files =
                fileRepository.findByUserIdAndIsDeletedTrueOrderByDeletedAtDesc(userId);

        // Batch-load folder names
        Set<UUID> folderIds = files.stream()
                .map(FileEntity::getFolderId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> folderNames = new HashMap<>();
        if (!folderIds.isEmpty()) {
            folderRepository.findAllById(folderIds)
                    .forEach(f -> folderNames.put(f.getId(), f.getName()));
        }

        return files.stream().map(f -> TrashItemDto.builder()
                .id(f.getId())
                .name(f.getName())
                .size(f.getSize())
                .contentType(f.getContentType())
                .folderId(f.getFolderId())
                .folderName(f.getFolderId() != null ? folderNames.get(f.getFolderId()) : null)
                .deletedAt(f.getDeletedAt())
                .build()).collect(Collectors.toList());
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    @Transactional
    public TrashItemDto restore(UUID fileId, UUID userId) {
        FileEntity file = fileRepository.findByIdAndUserIdAndIsDeletedTrue(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found in trash"));

        // No quota check: trashed files already count against the limit
        // (UserService#storedBytes), so restoring adds no stored bytes.

        // The file's folder may have been deleted while it sat in trash (folder
        // delete soft-deletes its files but hard-deletes the folder itself).
        // Restoring into a folderId that no longer exists would make the file
        // invisible — neither at root nor reachable via any folder. Fall back to root.
        UUID restoreFolderId = file.getFolderId();
        if (restoreFolderId != null
                && folderRepository.findByIdAndUserId(restoreFolderId, userId).isEmpty()) {
            restoreFolderId = null;
            file.setFolderId(null);
        }

        file.setIsDeleted(false);
        file.setDeletedAt(null);
        fileRepository.save(file);
        userService.updateStorageUsed(userId, file.getSize());
        activityService.log(userId, ActivityService.RESTORE, file.getName(), file.getSize());
        fileService.evictFileCaches(userId, restoreFolderId);

        return TrashItemDto.builder()
                .id(file.getId()).name(file.getName()).size(file.getSize())
                .contentType(file.getContentType()).folderId(restoreFolderId)
                .deletedAt(null).build();
    }

    // ── Permanently delete one file ───────────────────────────────────────────

    @Transactional
    public void hardDelete(UUID fileId, UUID userId) {
        FileEntity file = fileRepository.findByIdAndUserIdAndIsDeletedTrue(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found in trash"));
        activityService.log(userId, ActivityService.PERMANENT_DELETE, file.getName(), file.getSize());
        hardDeleteInternal(file);
    }

    // ── Empty trash ───────────────────────────────────────────────────────────

    @Transactional
    public void emptyTrash(UUID userId) {
        List<FileEntity> files =
                fileRepository.findByUserIdAndIsDeletedTrueOrderByDeletedAtDesc(userId);
        for (FileEntity file : files) {
            activityService.log(userId, ActivityService.PERMANENT_DELETE, file.getName(), file.getSize());
            hardDeleteInternal(file);
        }
    }

    // ── Scheduled cleanup: permanently delete files older than 30 days ────────
    // Called by SchedulerService — returns count of deleted files.

    @Transactional
    public int cleanupExpiredTrash() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        List<FileEntity> expired = fileRepository.findByIsDeletedTrueAndDeletedAtBefore(cutoff);
        for (FileEntity file : expired) {
            hardDeleteInternal(file);
        }
        return expired.size();
    }

    // ── Internal hard-delete (no auth check, no extra activity log) ───────────

    private void hardDeleteInternal(FileEntity file) {
        // Every object this file points at: its current copy and all its
        // versions. (Versions used to vanish through the database cascade
        // alone, which left their objects in the bucket forever.)
        List<FileVersionEntity> versions = versionRepository.findByFileIdOrderByVersionDesc(file.getId());
        Set<String> keys = new LinkedHashSet<>();
        keys.add(file.getStorageKey());
        versions.forEach(v -> keys.add(v.getStorageKey()));

        versionRepository.deleteAll(versions);
        fileRepository.delete(file);

        // Objects another file or version still uses (dedup) are kept.
        keys.forEach(storageCleanupService::deleteIfUnreferenced);
    }
}
