package com.systemdesign.dropbox.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in the CompleteUploadRequest chunk etag list.
 *
 * The client obtains the etag from the ETag response header of the S3 PUT request.
 * S3 requires all part etags to reconstruct the final object in CompleteMultipartUpload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkEtag {

    @NotNull
    @Min(1)
    private Integer chunkNumber;

    @NotBlank(message = "etag must not be blank")
    private String etag;
}
