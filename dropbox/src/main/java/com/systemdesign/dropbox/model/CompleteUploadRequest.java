package com.systemdesign.dropbox.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Client sends this to POST /files/{fileId}/complete to finalize a chunked upload.
 *
 * chunkEtags must contain exactly one entry per chunk (totalChunks entries).
 * The backend sorts them by chunkNumber before calling S3 CompleteMultipartUpload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteUploadRequest {

    @NotEmpty(message = "chunkEtags must not be empty")
    @Valid
    private List<ChunkEtag> chunkEtags;

    /** Optional — SHA-256 of the complete assembled file for end-to-end verification. */
    private String checksum;
}
