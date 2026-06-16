package com.systemdesign.dropbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from POST /files/initiate.
 *
 * The client must:
 * 1. PUT each chunk to its corresponding presignedUrl in chunkUrls.
 * 2. Collect the ETag from each PUT response.
 * 3. Call POST /files/{fileId}/complete with all ETags to finalize.
 *
 * totalChunks is informational — always equals chunkUrls.size().
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitiateUploadResponse {

    private String fileId;
    private int totalChunks;

    /** Ordered list of (chunkNumber, presignedUrl) pairs. */
    private List<PresignedChunkUrl> chunkUrls;
}
