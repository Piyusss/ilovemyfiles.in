package in.ilovemyfiles.file;

import in.ilovemyfiles.analytics.ActivityService;
import in.ilovemyfiles.common.BadRequestException;
import in.ilovemyfiles.common.NotFoundException;
import in.ilovemyfiles.storage.StorageCleanupService;
import in.ilovemyfiles.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileVersionService {

    private final FileRepository fileRepository;
    private final FileVersionRepository versionRepository;
    private final FileService fileService;
    private final UserService userService;
    private final ActivityService activityService;
    private final StorageCleanupService storageCleanupService;

    public List<FileVersionDto> getVersions(UUID fileId, UUID userId) {
        FileEntity file = fileRepository.findByIdAndUserIdAndIsDeletedFalse(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found"));

        return versionRepository.findByFileIdOrderByVersionDesc(fileId).stream()
                .map(v -> toDto(v, v.getStorageKey().equals(file.getStorageKey())))
                .collect(Collectors.toList());
    }

    @Transactional
    public FileDto restore(UUID fileId, UUID versionId, UUID userId) {
        // Locked, so a concurrent deleteVersion can't remove the version this
        // restore is about to make current.
        FileEntity file = fileRepository.findLockedByIdAndUserIdAndIsDeletedFalse(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found"));

        FileVersionEntity target = versionRepository.findByIdAndFileId(versionId, fileId)
                .orElseThrow(() -> new NotFoundException("Version not found"));

        if (target.getStorageKey().equals(file.getStorageKey())) {
            return fileService.toDto(file); // already current
        }

        long delta = target.getSize() - file.getSize();
        file.setStorageKey(target.getStorageKey());
        file.setSize(target.getSize());
        fileRepository.save(file);

        if (delta != 0) userService.updateStorageUsed(userId, delta);
        activityService.log(userId, ActivityService.VERSION_RESTORE, file.getName(), target.getSize());
        // The cached folder listing still shows the old size.
        fileService.evictFileCaches(userId, file.getFolderId());
        return fileService.toDto(file);
    }

    @Transactional
    public void deleteVersion(UUID fileId, UUID versionId, UUID userId) {
        // Locked, so the "current version" check below can't race a restore.
        FileEntity file = fileRepository.findLockedByIdAndUserIdAndIsDeletedFalse(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found"));

        FileVersionEntity version = versionRepository.findByIdAndFileId(versionId, fileId)
                .orElseThrow(() -> new NotFoundException("Version not found"));

        if (version.getStorageKey().equals(file.getStorageKey())) {
            throw new BadRequestException("Cannot delete the current version");
        }

        if (versionRepository.countByFileId(fileId) <= 1) {
            throw new BadRequestException("Cannot delete the only version");
        }

        versionRepository.delete(version);
        // Dedup may share this object with other files, even other users' files:
        // it is deleted only once nothing references it any more.
        storageCleanupService.deleteIfUnreferenced(version.getStorageKey());
    }

    private FileVersionDto toDto(FileVersionEntity v, boolean current) {
        return FileVersionDto.builder()
                .id(v.getId()).fileId(v.getFileId())
                .version(v.getVersion()).size(v.getSize())
                .createdAt(v.getCreatedAt()).current(current)
                .build();
    }
}
