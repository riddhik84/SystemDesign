package com.systemdesign.dropbox.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Core metadata record for every file stored in the system.
 *
 * The s3Key follows the pattern: "files/{ownerId}/{fileId}"
 * A multipart upload stays open (s3UploadId != null) until the client calls /complete.
 * Once READY, s3UploadId is cleared and the file is fully accessible.
 */
@Entity
@Table(name = "file_metadata")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key — UUID generated at initiate time. Never changes after creation. */
    @Column(name = "file_id", nullable = false, unique = true, length = 36)
    private String fileId;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "mime_type", length = 200)
    private String mimeType;

    /** User who owns the file. Extracted from X-User-Id header at upload time. */
    @Column(name = "owner_id", nullable = false, length = 200)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileStatus status;

    /** Object key in S3 bucket. Pattern: files/{ownerId}/{fileId} */
    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    /** S3 multipart upload ID. Non-null while UPLOADING, cleared after completion. */
    @Column(name = "s3_upload_id", length = 200)
    private String s3UploadId;

    /** Number of chunks the file was split into (ceil(fileSizeBytes / CHUNK_SIZE)). */
    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks;

    /** SHA-256 of the complete file, provided by client on /complete. */
    @Column(name = "checksum", length = 64)
    private String checksum;

    /** Whether the file was compressed before upload (client responsibility). */
    @Column(nullable = false)
    private boolean compressed;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
