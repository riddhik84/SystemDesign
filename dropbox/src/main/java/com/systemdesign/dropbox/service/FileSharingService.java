package com.systemdesign.dropbox.service;

import com.systemdesign.dropbox.exception.AccessDeniedException;
import com.systemdesign.dropbox.exception.FileNotFoundException;
import com.systemdesign.dropbox.model.FileChangeEvent;
import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.FileStatus;
import com.systemdesign.dropbox.model.ShareRequest;
import com.systemdesign.dropbox.model.SharedFile;
import com.systemdesign.dropbox.repository.FileMetadataRepository;
import com.systemdesign.dropbox.repository.SharedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Manages file sharing and access-control checks.
 *
 * Access model:
 *   - File owner always has full access (implicit — no SharedFile row needed).
 *   - Any other user needs an explicit SharedFile record to access a file.
 *   - Permission field ("READ"/"WRITE") is stored but enforcement is limited to
 *     READ in this reference implementation. WRITE would allow the recipient to
 *     initiate uploads of new versions.
 *
 * Sharing publishes a SHARED event to the recipient's Redis channel so their
 * WebSocket-connected devices see the new file immediately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSharingService {

    private final FileMetadataRepository fileMetadataRepository;
    private final SharedFileRepository sharedFileRepository;
    private final FileSyncService fileSyncService;

    /**
     * Grants targetUserId access to the file.
     * Only the file owner can share.
     *
     * @param ownerId sharing user (must be the file owner)
     * @param fileId  file to share
     * @param req     target user and permission level
     */
    @Transactional
    public void shareFile(String ownerId, String fileId, ShareRequest req) {
        FileMetadata metadata = fileMetadataRepository.findByFileId(fileId)
                .orElseThrow(() -> new FileNotFoundException(fileId));

        if (!metadata.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException(
                    "Only the file owner can share the file: " + fileId);
        }

        if (metadata.getStatus() != FileStatus.READY) {
            throw new IllegalArgumentException(
                    "Cannot share a file that is not READY: " + fileId);
        }

        // Upsert: delete existing record if present, then insert fresh.
        sharedFileRepository.deleteByFileIdAndSharedWithUserId(fileId, req.getTargetUserId());

        SharedFile share = SharedFile.builder()
                .fileId(fileId)
                .sharedWithUserId(req.getTargetUserId())
                .sharedByUserId(ownerId)
                .permission(req.getPermission())
                .build();
        sharedFileRepository.save(share);

        log.info("File shared: fileId={}, by={}, with={}, permission={}",
                fileId, ownerId, req.getTargetUserId(), req.getPermission());

        // Notify the recipient's connected devices.
        FileChangeEvent event = FileChangeEvent.builder()
                .fileId(fileId)
                .fileName(metadata.getFileName())
                .ownerId(ownerId)
                .eventType("SHARED")
                .fileStatus(FileStatus.READY)
                .occurredAt(Instant.now())
                .build();
        fileSyncService.publishChange(req.getTargetUserId(), event);
    }

    /**
     * Revokes a previously granted share.
     * Only the file owner can unshare.
     *
     * @param ownerId      must be the file owner
     * @param fileId       file to unshare
     * @param targetUserId user to revoke access from
     */
    @Transactional
    public void unshareFile(String ownerId, String fileId, String targetUserId) {
        FileMetadata metadata = fileMetadataRepository.findByFileId(fileId)
                .orElseThrow(() -> new FileNotFoundException(fileId));

        if (!metadata.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException(
                    "Only the file owner can unshare the file: " + fileId);
        }

        sharedFileRepository.deleteByFileIdAndSharedWithUserId(fileId, targetUserId);

        log.info("File unshared: fileId={}, removedUser={}", fileId, targetUserId);
    }

    /**
     * Returns whether the given user has access to the file.
     *
     * Access is granted if:
     *   a) userId equals the ownerId (implicit owner access), or
     *   b) A SharedFile record exists with (fileId, userId).
     *
     * @param userId  requesting user
     * @param fileId  file being accessed
     * @param ownerId owner of the file (from FileMetadata)
     * @return true if access is granted
     */
    public boolean hasAccess(String userId, String fileId, String ownerId) {
        if (userId.equals(ownerId)) {
            return true;
        }
        return sharedFileRepository.existsByFileIdAndSharedWithUserId(fileId, userId);
    }

    /**
     * Returns all fileIds that have been shared with the given user.
     * Used by FileSyncService to include shared files in change queries.
     */
    public List<String> getSharedFileIds(String userId) {
        return sharedFileRepository.findBySharedWithUserId(userId)
                .stream()
                .map(SharedFile::getFileId)
                .toList();
    }
}
