package com.systemdesign.dropbox.service;

import com.amazonaws.services.s3.model.PartETag;
import com.systemdesign.dropbox.config.AppProperties;
import com.systemdesign.dropbox.exception.AccessDeniedException;
import com.systemdesign.dropbox.exception.FileNotFoundException;
import com.systemdesign.dropbox.model.ChunkEtag;
import com.systemdesign.dropbox.model.CompleteUploadRequest;
import com.systemdesign.dropbox.model.FileChangeEvent;
import com.systemdesign.dropbox.model.FileChunk;
import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.FileStatus;
import com.systemdesign.dropbox.model.InitiateUploadRequest;
import com.systemdesign.dropbox.model.InitiateUploadResponse;
import com.systemdesign.dropbox.model.PresignedChunkUrl;
import com.systemdesign.dropbox.repository.FileChunkRepository;
import com.systemdesign.dropbox.repository.FileMetadataRepository;
import com.systemdesign.dropbox.storage.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the two-phase chunked upload flow.
 *
 * Phase 1 — initiateUpload:
 *   1. Calculate chunk count from file size.
 *   2. Initiate S3 multipart upload → get uploadId.
 *   3. Persist FileMetadata (UPLOADING) + FileChunk rows (uploaded=false).
 *   4. Generate one presigned PUT URL per chunk.
 *   5. Return fileId + presigned URL list to client.
 *
 * Phase 2 — completeUpload (called after client uploads all chunks directly to S3):
 *   1. Load and validate FileMetadata ownership.
 *   2. Update FileChunk rows with client-provided ETags.
 *   3. Call S3 CompleteMultipartUpload with sorted PartETags.
 *   4. Mark file READY, clear uploadId.
 *   5. Publish FileChangeEvent to Redis so connected clients sync.
 *
 * Why direct-to-S3 uploads?
 *   - Files up to 50 GB would saturate application server bandwidth and memory
 *     if routed through the service tier. Presigned URLs push the data path
 *     directly to S3, keeping the service stateless and horizontally scalable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadService {

    private final FileMetadataRepository fileMetadataRepository;
    private final FileChunkRepository fileChunkRepository;
    private final S3StorageService s3StorageService;
    private final FileSyncService fileSyncService;
    private final AppProperties appProperties;

    /**
     * Phase 1: initiate a chunked upload.
     *
     * @param ownerId the userId from X-User-Id header
     * @param req     upload parameters (fileName, fileSizeBytes, mimeType, checksum)
     * @return fileId and list of presigned URLs, one per chunk
     */
    @Transactional
    public InitiateUploadResponse initiateUpload(String ownerId, InitiateUploadRequest req) {
        long chunkSizeBytes = appProperties.getChunkSizeBytes();
        int totalChunks = (int) Math.ceil((double) req.getFileSizeBytes() / chunkSizeBytes);
        totalChunks = Math.max(totalChunks, 1);   // single-chunk edge case for tiny files

        String fileId = UUID.randomUUID().toString();
        String s3Key = "files/" + ownerId + "/" + fileId;

        log.info("Initiating upload: ownerId={}, fileName={}, sizeBytes={}, totalChunks={}",
                ownerId, req.getFileName(), req.getFileSizeBytes(), totalChunks);

        // Start the S3 multipart upload — this reserves the upload slot on S3 side.
        String uploadId = s3StorageService.initiateMultipartUpload(s3Key);

        // Persist metadata in UPLOADING state.
        FileMetadata metadata = FileMetadata.builder()
                .fileId(fileId)
                .fileName(req.getFileName())
                .fileSizeBytes(req.getFileSizeBytes())
                .mimeType(req.getMimeType())
                .ownerId(ownerId)
                .status(FileStatus.UPLOADING)
                .s3Key(s3Key)
                .s3UploadId(uploadId)
                .totalChunks(totalChunks)
                .checksum(req.getChecksum())
                .compressed(req.isCompressed())
                .build();
        fileMetadataRepository.save(metadata);

        // Persist one FileChunk row per part, then generate presigned URLs.
        Duration urlExpiry = Duration.ofMinutes(
                appProperties.getS3().getPresignedUrlExpiryMinutes());

        List<PresignedChunkUrl> chunkUrls = new ArrayList<>(totalChunks);

        for (int partNumber = 1; partNumber <= totalChunks; partNumber++) {
            long chunkSize = calculateChunkSize(req.getFileSizeBytes(), chunkSizeBytes,
                    partNumber, totalChunks);

            FileChunk chunk = FileChunk.builder()
                    .fileId(fileId)
                    .chunkNumber(partNumber)
                    .chunkSizeBytes(chunkSize)
                    .uploaded(false)
                    .build();
            fileChunkRepository.save(chunk);

            String presignedUrl = s3StorageService.generatePresignedUploadUrl(
                    s3Key, uploadId, partNumber, urlExpiry);

            chunkUrls.add(PresignedChunkUrl.builder()
                    .chunkNumber(partNumber)
                    .presignedUrl(presignedUrl)
                    .build());
        }

        log.info("Upload initiated: fileId={}, totalChunks={}", fileId, totalChunks);

        return InitiateUploadResponse.builder()
                .fileId(fileId)
                .totalChunks(totalChunks)
                .chunkUrls(chunkUrls)
                .build();
    }

    /**
     * Phase 2: complete a chunked upload after client has PUT all chunks to S3.
     *
     * @param ownerId requesting user (must match FileMetadata.ownerId)
     * @param fileId  the fileId from the initiate response
     * @param req     ETags for all uploaded chunks
     * @return updated FileMetadata in READY state
     */
    @Transactional
    public FileMetadata completeUpload(String ownerId, String fileId,
                                       CompleteUploadRequest req) {
        FileMetadata metadata = fileMetadataRepository.findByFileId(fileId)
                .orElseThrow(() -> new FileNotFoundException(fileId));

        if (!metadata.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException(ownerId, fileId);
        }

        int expectedChunks = metadata.getTotalChunks();
        if (req.getChunkEtags().size() != expectedChunks) {
            throw new IllegalArgumentException(
                    "Expected " + expectedChunks + " chunk etags, got " +
                    req.getChunkEtags().size());
        }

        // Build a map for quick lookup: chunkNumber -> etag
        Map<Integer, String> etagMap = req.getChunkEtags().stream()
                .collect(Collectors.toMap(ChunkEtag::getChunkNumber, ChunkEtag::getEtag));

        // Update each FileChunk record with its etag.
        List<FileChunk> chunks = fileChunkRepository.findByFileIdOrderByChunkNumber(fileId);
        for (FileChunk chunk : chunks) {
            String etag = etagMap.get(chunk.getChunkNumber());
            if (etag == null || etag.isBlank()) {
                throw new IllegalArgumentException(
                        "Missing etag for chunk " + chunk.getChunkNumber());
            }
            chunk.setEtag(etag);
            chunk.setUploaded(true);
            fileChunkRepository.save(chunk);
        }

        // Build the PartETag list sorted by part number (S3 requirement).
        List<PartETag> partETags = req.getChunkEtags().stream()
                .sorted(Comparator.comparingInt(ChunkEtag::getChunkNumber))
                .map(ce -> new PartETag(ce.getChunkNumber(), ce.getEtag()))
                .collect(Collectors.toList());

        // Complete the S3 multipart upload — S3 assembles the parts atomically.
        s3StorageService.completeMultipartUpload(
                metadata.getS3Key(), metadata.getS3UploadId(), partETags);

        // Mark the file as READY in the database.
        metadata.setStatus(FileStatus.READY);
        metadata.setS3UploadId(null);     // clear the multipart upload ID
        if (req.getChecksum() != null) {
            metadata.setChecksum(req.getChecksum());
        }
        FileMetadata saved = fileMetadataRepository.save(metadata);

        log.info("Upload completed: fileId={}, ownerId={}", fileId, ownerId);

        // Notify connected clients via Redis pub/sub → WebSocket push.
        FileChangeEvent event = FileChangeEvent.builder()
                .fileId(fileId)
                .fileName(saved.getFileName())
                .ownerId(ownerId)
                .eventType("CREATED")
                .fileStatus(FileStatus.READY)
                .occurredAt(Instant.now())
                .build();
        fileSyncService.publishChange(ownerId, event);

        return saved;
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Calculates the byte size of a specific chunk.
     * The last chunk may be smaller than the standard chunk size.
     */
    private long calculateChunkSize(long totalBytes, long chunkSizeBytes,
                                     int partNumber, int totalChunks) {
        if (partNumber == totalChunks) {
            long remainder = totalBytes % chunkSizeBytes;
            return remainder == 0 ? chunkSizeBytes : remainder;
        }
        return chunkSizeBytes;
    }
}
