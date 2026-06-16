package com.systemdesign.dropbox.repository;

import com.systemdesign.dropbox.model.FileChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA repository for FileChunk.
 *
 * Chunks are written once at initiate time and updated when the client
 * confirms upload (providing the ETag) via completeUpload.
 */
@Repository
public interface FileChunkRepository extends JpaRepository<FileChunk, Long> {

    List<FileChunk> findByFileIdOrderByChunkNumber(String fileId);

    Optional<FileChunk> findByFileIdAndChunkNumber(String fileId, int chunkNumber);

    /** Bulk delete when aborting or cleaning up an incomplete upload. */
    @Modifying
    @Query("DELETE FROM FileChunk c WHERE c.fileId = :fileId")
    void deleteByFileId(@Param("fileId") String fileId);

    /** Count chunks that have been confirmed uploaded — used for validation. */
    long countByFileIdAndUploadedTrue(String fileId);
}
