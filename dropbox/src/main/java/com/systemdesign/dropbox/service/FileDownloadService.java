package com.systemdesign.dropbox.service;

import com.systemdesign.dropbox.config.AppProperties;
import com.systemdesign.dropbox.exception.AccessDeniedException;
import com.systemdesign.dropbox.exception.FileNotFoundException;
import com.systemdesign.dropbox.exception.UploadIncompleteException;
import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.FileStatus;
import com.systemdesign.dropbox.repository.FileMetadataRepository;
import com.systemdesign.dropbox.storage.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Generates time-limited download URLs for authorized users.
 *
 * Download flow:
 *   1. Client calls GET /files/{fileId}/presigned-url with X-User-Id header.
 *   2. This service verifies the file exists and is READY.
 *   3. Access check: requester must be the owner OR have a SharedFile record.
 *   4. Generates a presigned GET URL (or CDN-signed URL in production).
 *   5. Client downloads directly from S3/CloudFront — bytes never traverse the app server.
 *
 * CDN vs direct S3:
 *   For production, replace generatePresignedDownloadUrl with CloudFront signed URLs.
 *   CloudFront caches popular files at edge locations, reducing S3 GET costs and latency.
 *   The signed URL mechanism ensures only authorized users can access cached content.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileDownloadService {

    private final FileMetadataRepository fileMetadataRepository;
    private final FileSharingService fileSharingService;
    private final S3StorageService s3StorageService;
    private final AppProperties appProperties;

    /**
     * Returns a presigned download URL for the given file if the requester has access.
     *
     * @param requesterId userId from X-User-Id header
     * @param fileId      file to download
     * @return presigned download URL string (valid for presignedUrlExpiryMinutes)
     * @throws FileNotFoundException      if fileId doesn't exist or is DELETED
     * @throws AccessDeniedException      if requester is not owner and has no share grant
     * @throws UploadIncompleteException  if file is still UPLOADING/PENDING
     */
    @Transactional(readOnly = true)
    public String getDownloadUrl(String requesterId, String fileId) {
        FileMetadata metadata = fileMetadataRepository.findByFileId(fileId)
                .orElseThrow(() -> new FileNotFoundException(fileId));

        if (metadata.getStatus() == FileStatus.DELETED) {
            throw new FileNotFoundException(fileId);
        }

        if (metadata.getStatus() != FileStatus.READY) {
            throw new UploadIncompleteException(fileId);
        }

        if (!fileSharingService.hasAccess(requesterId, fileId, metadata.getOwnerId())) {
            throw new AccessDeniedException(requesterId, fileId);
        }

        Duration expiry = Duration.ofMinutes(
                appProperties.getS3().getPresignedUrlExpiryMinutes());

        String url = s3StorageService.generatePresignedDownloadUrl(metadata.getS3Key(), expiry);

        log.info("Download URL generated: fileId={}, requesterId={}", fileId, requesterId);
        return url;
    }
}
