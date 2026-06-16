package com.systemdesign.dropbox.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client sends this to POST /files/initiate to start a chunked upload.
 *
 * fileSizeBytes must be > 0. The backend uses it to calculate how many chunks
 * are needed (ceil(fileSizeBytes / CHUNK_SIZE_BYTES)).
 *
 * checksum is the SHA-256 of the full file computed client-side before upload.
 * It is stored on FileMetadata after completion for integrity verification.
 *
 * compressed indicates whether the client pre-compressed the file. The backend
 * treats the bytes as opaque — it does not decompress on download.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitiateUploadRequest {

    @NotBlank(message = "fileName is required")
    private String fileName;

    @NotNull(message = "fileSizeBytes is required")
    @Min(value = 1, message = "fileSizeBytes must be positive")
    private Long fileSizeBytes;

    private String mimeType;

    /** SHA-256 hex of the complete file — provided upfront for integrity tracking. */
    private String checksum;

    @Builder.Default
    private boolean compressed = false;
}
