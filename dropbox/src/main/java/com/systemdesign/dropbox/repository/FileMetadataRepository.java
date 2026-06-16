package com.systemdesign.dropbox.repository;

import com.systemdesign.dropbox.model.FileMetadata;
import com.systemdesign.dropbox.model.FileStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA repository for FileMetadata.
 *
 * Custom queries support the /files/changes polling endpoint.
 * The JPQL queries mirror the indexed columns in schema.sql for performance.
 */
@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {

    Optional<FileMetadata> findByFileId(String fileId);

    /** Used by sync: files owned by this user updated after a given timestamp. */
    List<FileMetadata> findByOwnerIdAndStatusAndUpdatedAtAfter(
            String ownerId, FileStatus status, LocalDateTime since);

    /** Used by sync: files in a given id set updated after a given timestamp. */
    @Query("SELECT f FROM FileMetadata f WHERE f.fileId IN :fileIds AND f.status = :status AND f.updatedAt > :since")
    List<FileMetadata> findByFileIdInAndStatusAndUpdatedAtAfter(
            @Param("fileIds") List<String> fileIds,
            @Param("status") FileStatus status,
            @Param("since") LocalDateTime since);

    /** Returns all READY files for a given owner — used for listing. */
    List<FileMetadata> findByOwnerIdAndStatus(String ownerId, FileStatus status);
}
