package com.systemdesign.dropbox.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Access-control record granting a user access to a file they don't own.
 *
 * The composite PK (fileId, sharedWithUserId) enforces that a file can only
 * be shared once with a given user — re-sharing overwrites via upsert semantics.
 *
 * permission values: "READ" (download only), "WRITE" (download + modify).
 * In this reference implementation only READ is exercised, but WRITE is modeled
 * for completeness.
 */
@Entity
@Table(
    name = "shared_files",
    indexes = @Index(name = "idx_shared_files_user_id", columnList = "shared_with_user_id")
)
@IdClass(SharedFileId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SharedFile {

    @Id
    @Column(name = "file_id", nullable = false, length = 36)
    private String fileId;

    @Id
    @Column(name = "shared_with_user_id", nullable = false, length = 200)
    private String sharedWithUserId;

    @Column(name = "shared_by_user_id", nullable = false, length = 200)
    private String sharedByUserId;

    /** "READ" or "WRITE". */
    @Column(nullable = false, length = 10)
    private String permission;

    @Column(name = "shared_at", nullable = false)
    private LocalDateTime sharedAt;

    @PrePersist
    public void prePersist() {
        this.sharedAt = LocalDateTime.now();
    }
}
