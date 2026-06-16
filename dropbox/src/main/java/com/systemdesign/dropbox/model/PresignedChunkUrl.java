package com.systemdesign.dropbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in the InitiateUploadResponse chunk list.
 *
 * The client uploads the bytes for chunkNumber directly to presignedUrl via HTTP PUT.
 * S3 returns an ETag header in the PUT response — the client must collect these
 * and include them in CompleteUploadRequest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresignedChunkUrl {

    /** 1-based chunk index matching S3 part numbers. */
    private int chunkNumber;

    /** Pre-signed S3 PUT URL, valid for the configured presigned-url-expiry-minutes. */
    private String presignedUrl;
}
