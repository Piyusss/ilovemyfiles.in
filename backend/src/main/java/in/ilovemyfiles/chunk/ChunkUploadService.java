package in.ilovemyfiles.chunk;

import in.ilovemyfiles.analytics.ActivityService;
import in.ilovemyfiles.common.BadRequestException;
import in.ilovemyfiles.common.NotFoundException;
import in.ilovemyfiles.notification.NotificationService;
import in.ilovemyfiles.file.FileDto;
import in.ilovemyfiles.file.FileService;
import in.ilovemyfiles.file.PhysicalFileEntity;
import in.ilovemyfiles.file.PhysicalFileRepository;
import in.ilovemyfiles.storage.StorageCapacityService;
import in.ilovemyfiles.storage.StorageService;
import in.ilovemyfiles.user.User;
import in.ilovemyfiles.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Coordinates direct-to-storage uploads via S3 multipart. The browser uploads
 * parts straight to object storage using presigned URLs; this service only
 * orchestrates (create / presign / assemble) and writes metadata. File bytes
 * never pass through the app, and the merge happens storage-side in complete().
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkUploadService {

    private final UploadSessionRepository sessionRepository;
    private final FileService fileService;
    private final PhysicalFileRepository physicalFileRepository;
    private final UserService userService;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final StorageService storageService;
    private final StorageCapacityService storageCapacityService;

    // S3 parts are at least 5 MB (only the last may be smaller).
    private static final long MIN_PART_SIZE = 5L * 1024 * 1024;

    @Value("${app.max-upload-size:75MB}")
    private DataSize maxUploadSize;

    // ── Init: dedup check, else open a multipart upload and presign the parts ──

    @Transactional
    public InitUploadResponse init(InitUploadRequest req, UUID userId) {
        // ── Size limits — before dedup, so the limit holds for every upload ───
        if (req.getFileSize() > maxUploadSize.toBytes()) {
            throw new BadRequestException(
                    "Files larger than " + maxUploadSize.toMegabytes() + " MB can't be uploaded");
        }
        // complete() rejects objects whose real size differs from fileSize, but the
        // client picks totalChunks; cap it so we never presign more parts than the
        // declared size could need.
        long maxParts = Math.max(1, (req.getFileSize() + MIN_PART_SIZE - 1) / MIN_PART_SIZE);
        if (req.getTotalChunks() > maxParts) {
            throw new BadRequestException("Too many parts for a file of this size");
        }

        // ── Account quota — also before dedup: a deduplicated file still counts ──
        User user = userService.findById(userId);
        userService.checkQuota(user, req.getFileSize());

        // ── App-wide ceiling — locks until this transaction commits, so the
        //    session or file row written below is visible to the next check ──
        storageCapacityService.checkCapacity(req.getFileSize());

        // ── Deduplication check ──────────────────────────────────────────────
        if (req.getHash() != null) {
            var existing = physicalFileRepository.findByHash(req.getHash());
            if (existing.isPresent()) {
                PhysicalFileEntity pf = existing.get();
                pf.setRefCount(pf.getRefCount() + 1);
                physicalFileRepository.save(pf);

                FileDto fileItem = fileService.createOrUpdateFile(
                        req.getFileName(), req.getFileSize(), req.getContentType(),
                        req.getFolderId(), userId, pf.getStorageKey(), pf.getId(), true);

                // Dedup still creates a new file row — evict the cached listing so
                // it shows up immediately (mirrors complete()).
                fileService.evictFileCaches(userId, req.getFolderId());

                return InitUploadResponse.builder()
                        .duplicate(true)
                        .fileItem(fileItem)
                        .parts(List.of())
                        .build();
            }
        }

        // ── Normal path ──────────────────────────────────────────────────────
        String storageKey = userId + "/" + UUID.randomUUID();
        String s3UploadId = storageService.createMultipartUpload(storageKey, req.getContentType());

        // Flush now so a failed insert surfaces here, not at commit, and the
        // storage-side upload can be aborted instead of left dangling.
        UploadSessionEntity session;
        try {
            session = sessionRepository.saveAndFlush(UploadSessionEntity.builder()
                    .userId(userId).fileName(req.getFileName()).fileSize(req.getFileSize())
                    .totalChunks(req.getTotalChunks()).contentType(req.getContentType())
                    .folderId(req.getFolderId()).storageKey(storageKey)
                    .s3UploadId(s3UploadId).hash(req.getHash())
                    .build());
        } catch (RuntimeException e) {
            try {
                storageService.abortMultipartUpload(storageKey, s3UploadId);
            } catch (Exception abortError) {
                log.warn("Abort multipart failed for {}: {}", storageKey, abortError.getMessage());
            }
            throw e;
        }

        List<PartUrl> parts = new ArrayList<>(req.getTotalChunks());
        for (int partNumber = 1; partNumber <= req.getTotalChunks(); partNumber++) {
            parts.add(new PartUrl(partNumber,
                    storageService.presignUploadPart(storageKey, s3UploadId, partNumber)));
        }

        return InitUploadResponse.builder()
                .uploadId(session.getId().toString())
                .key(storageKey)
                .parts(parts)
                .duplicate(false)
                .build();
    }

    // ── Status (for resume) — which parts has storage already received? ───────

    public UploadStatusDto status(UUID uploadId, UUID userId) {
        UploadSessionEntity session = sessionRepository.findByIdAndUserId(uploadId, userId)
                .orElseThrow(() -> new NotFoundException("Upload session not found"));

        List<Integer> uploaded = List.of();
        if ("in_progress".equals(session.getStatus()) && session.getS3UploadId() != null) {
            uploaded = storageService.listParts(session.getStorageKey(), session.getS3UploadId())
                    .stream().map(CompletedPart::partNumber).toList();
        }

        return UploadStatusDto.builder()
                .uploadId(session.getId().toString())
                .fileName(session.getFileName())
                .fileSize(session.getFileSize())
                .totalChunks(session.getTotalChunks())
                .uploadedChunks(uploaded)
                .status(session.getStatus())
                .build();
    }

    // ── Complete: assemble parts storage-side, then write metadata ────────────
    // NOT @Transactional: the storage assembly must not hold a DB connection.

    public FileDto complete(UUID uploadId, UUID userId) {
        UploadSessionEntity session = sessionRepository
                .findByIdAndUserIdAndStatus(uploadId, userId, "in_progress")
                .orElseThrow(() -> new NotFoundException("Upload session not found or already completed"));

        List<CompletedPart> parts = storageService.listParts(session.getStorageKey(), session.getS3UploadId());
        if (parts.isEmpty()) {
            throw new BadRequestException("No uploaded parts found for this session");
        }
        storageService.completeMultipartUpload(session.getStorageKey(), session.getS3UploadId(), parts);

        long actualSize = storageService.headObjectSize(session.getStorageKey());
        if (actualSize != session.getFileSize()) {
            storageService.deleteObject(session.getStorageKey());
            throw new BadRequestException("Uploaded file is incomplete (size mismatch)");
        }

        // Short metadata transaction (physical-file + file + version), out of the storage path.
        FileDto result = fileService.finalizeUpload(
                session.getFileName(), session.getFileSize(), session.getContentType(),
                session.getFolderId(), userId, session.getStorageKey(), session.getHash());

        session.setStatus("completed");
        sessionRepository.save(session);

        // Post-commit side effects.
        fileService.evictFileCaches(userId, session.getFolderId());
        activityService.log(userId, ActivityService.UPLOAD, session.getFileName(), session.getFileSize());
        notificationService.send(userId, NotificationService.UPLOAD_COMPLETE,
                "Upload complete", session.getFileName() + " has been uploaded successfully.",
                result.getId().toString(), session.getFileName());
        fileService.checkStorageWarning(userId);
        return result;
    }

    // ── Cancel: abort the multipart upload and drop the session ───────────────

    @Transactional
    public void cancel(UUID uploadId, UUID userId) {
        UploadSessionEntity session = sessionRepository.findByIdAndUserId(uploadId, userId)
                .orElseThrow(() -> new NotFoundException("Upload session not found"));

        if ("completed".equals(session.getStatus())) {
            throw new BadRequestException("Cannot cancel a completed upload");
        }

        if (session.getS3UploadId() != null) {
            try {
                storageService.abortMultipartUpload(session.getStorageKey(), session.getS3UploadId());
            } catch (Exception e) {
                log.warn("Abort multipart failed for {}: {}", uploadId, e.getMessage());
            }
        }

        sessionRepository.deleteById(uploadId);
    }
}
