package com.systemdesign.dropbox.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tracks individual chunk state for a multipart upload.
 *
 * One row per chunk per file. The etag is returned by S3 when the client
 * successfully uploads the part. We persist etags here so we can reconstruct
 * the PartETag list needed to call CompleteMultipartUpload.
 *
 * chunkNumber is 1-based to match S3's part numbering convention.
 */
@Entity
@Table(
    name = "file_chunks",
    uniqueConstraints = @UniqueConstraint(columnNames = {"file_id", "chunk_number"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Matches FileMetadata.fileId — not a JPA FK to keep inserts fast at scale. */
    @Column(name = "file_id", nullable = false, length = 36)
    private String fileId;

    /** 1-based part number, matching S3 multipart part numbers. */
    @Column(name = "chunk_number", nullable = false)
    private Integer chunkNumber;

    /** ETag returned by S3 after the part upload. Null until the client uploads. */
    @Column(name = "etag", length = 200)
    private String etag;

    @Column(name = "chunk_size_bytes")
    private Long chunkSizeBytes;

    /** SHA-256 of this individual chunk — used for resumable upload deduplication. */
    @Column(name = "checksum", length = 64)
    private String checksum;

    /** Set to true when the client confirms upload and provides the etag. */
    @Column(nullable = false)
    private boolean uploaded;
}
