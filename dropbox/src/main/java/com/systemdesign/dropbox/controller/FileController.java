package com.systemdesign.dropbox.controller;

import com.systemdesign.dropbox.model.CompleteUploadRequest;
import com.systemdesign.dropbox.model.FileChangeEvent;
import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.InitiateUploadRequest;
import com.systemdesign.dropbox.model.InitiateUploadResponse;
import com.systemdesign.dropbox.model.ShareRequest;
import com.systemdesign.dropbox.service.FileDownloadService;
import com.systemdesign.dropbox.service.FileSharingService;
import com.systemdesign.dropbox.service.FileSyncService;
import com.systemdesign.dropbox.service.FileUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for file upload, download, sharing, and sync.
 *
 * Authentication:
 *   All endpoints require an X-User-Id header. In this reference implementation
 *   the value is trusted as-is. Production would validate a JWT and extract the
 *   subject claim instead.
 *
 * Endpoint summary:
 *   POST /files/initiate                     → start chunked upload
 *   POST /files/{fileId}/complete            → finalize chunked upload
 *   GET  /files/{fileId}/presigned-url       → get download URL
 *   POST /files/{fileId}/share               → share file with a user
 *   DELETE /files/{fileId}/share/{userId}    → revoke a share
 *   GET  /files/changes?since={iso-instant}  → poll for sync changes
 */
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "File upload, download, sharing, and sync operations")
public class FileController {

    private final FileUploadService fileUploadService;
    private final FileDownloadService fileDownloadService;
    private final FileSharingService fileSharingService;
    private final FileSyncService fileSyncService;

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    /**
     * Initiates a chunked multipart upload.
     *
     * Returns one presigned PUT URL per chunk. The client uploads each chunk
     * directly to S3, collects the ETags, then calls /complete.
     */
    @PostMapping("/initiate")
    @Operation(
        summary = "Initiate chunked upload",
        description = "Creates FileMetadata, initiates S3 multipart upload, returns presigned PUT URLs per chunk."
    )
    @ApiResponse(responseCode = "201", description = "Upload initiated successfully")
    public ResponseEntity<InitiateUploadResponse> initiateUpload(
            @Parameter(description = "Authenticated user ID", required = true)
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody InitiateUploadRequest request) {

        InitiateUploadResponse response = fileUploadService.initiateUpload(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Completes a chunked upload after all parts have been uploaded to S3.
     *
     * The client provides the ETag for each chunk (obtained from S3's PUT response).
     * The backend calls S3 CompleteMultipartUpload and marks the file READY.
     */
    @PostMapping("/{fileId}/complete")
    @Operation(
        summary = "Complete chunked upload",
        description = "Validates all chunk ETags, calls S3 CompleteMultipartUpload, marks file READY."
    )
    @ApiResponse(responseCode = "200", description = "File upload completed")
    public ResponseEntity<FileMetadata> completeUpload(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String fileId,
            @Valid @RequestBody CompleteUploadRequest request) {

        FileMetadata metadata = fileUploadService.completeUpload(userId, fileId, request);
        return ResponseEntity.ok(metadata);
    }

    // -------------------------------------------------------------------------
    // Download
    // -------------------------------------------------------------------------

    /**
     * Returns a presigned download URL for the file.
     *
     * The client downloads the file directly from S3 (or CloudFront in production)
     * using the returned URL. The URL is valid for app.s3.presigned-url-expiry-minutes.
     */
    @GetMapping("/{fileId}/presigned-url")
    @Operation(
        summary = "Get presigned download URL",
        description = "Returns a short-lived S3 (or CDN) presigned URL for direct download."
    )
    @ApiResponse(responseCode = "200", description = "URL generated")
    @ApiResponse(responseCode = "403", description = "User does not have access to this file")
    @ApiResponse(responseCode = "404", description = "File not found")
    @ApiResponse(responseCode = "409", description = "File upload not yet complete")
    public ResponseEntity<Map<String, String>> getPresignedDownloadUrl(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String fileId) {

        String url = fileDownloadService.getDownloadUrl(userId, fileId);
        return ResponseEntity.ok(Map.of("url", url));
    }

    // -------------------------------------------------------------------------
    // Sharing
    // -------------------------------------------------------------------------

    /**
     * Shares a file with another user.
     * Only the file owner can share.
     */
    @PostMapping("/{fileId}/share")
    @Operation(
        summary = "Share file",
        description = "Grants another user READ or WRITE access to the file."
    )
    @ApiResponse(responseCode = "204", description = "File shared successfully")
    public ResponseEntity<Void> shareFile(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String fileId,
            @Valid @RequestBody ShareRequest request) {

        fileSharingService.shareFile(userId, fileId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Revokes a previously granted share.
     * Only the file owner can unshare.
     */
    @DeleteMapping("/{fileId}/share/{targetUserId}")
    @Operation(
        summary = "Revoke file share",
        description = "Removes the share grant for the specified user."
    )
    @ApiResponse(responseCode = "204", description = "Share revoked successfully")
    public ResponseEntity<Void> unshareFile(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String fileId,
            @PathVariable String targetUserId) {

        fileSharingService.unshareFile(userId, fileId, targetUserId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Sync (polling fallback)
    // -------------------------------------------------------------------------

    /**
     * Returns all file changes for the authenticated user since the given timestamp.
     *
     * Covers files owned by the user AND files shared with the user.
     * Clients call this on reconnect (after a WebSocket gap) to catch up on missed events.
     *
     * @param since ISO-8601 instant, e.g. "2024-01-15T10:30:00Z"
     */
    @GetMapping("/changes")
    @Operation(
        summary = "Poll for file changes",
        description = "Returns FileChangeEvents since the given timestamp. Used as polling fallback when WebSocket is unavailable."
    )
    @ApiResponse(responseCode = "200", description = "List of change events (may be empty)")
    public ResponseEntity<List<FileChangeEvent>> getChanges(
            @RequestHeader("X-User-Id") String userId,
            @Parameter(description = "ISO-8601 instant for lower bound, e.g. 2024-01-01T00:00:00Z")
            @RequestParam Instant since) {

        List<FileChangeEvent> changes = fileSyncService.getChanges(userId, since);
        return ResponseEntity.ok(changes);
    }
}
