package com.systemdesign.dropbox.repository;

import com.systemdesign.dropbox.model.SharedFile;
import com.systemdesign.dropbox.model.SharedFileId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JPA repository for SharedFile access-control records.
 */
@Repository
public interface SharedFileRepository extends JpaRepository<SharedFile, SharedFileId> {

    /** Check if a specific user has been granted access to a file. */
    boolean existsByFileIdAndSharedWithUserId(String fileId, String sharedWithUserId);

    /** All files shared WITH a given user (for sync change queries). */
    List<SharedFile> findBySharedWithUserId(String sharedWithUserId);

    /** All users a file has been shared with (for listing shares). */
    List<SharedFile> findByFileId(String fileId);

    /** Remove a specific share grant. */
    @Modifying
    @Query("DELETE FROM SharedFile s WHERE s.fileId = :fileId AND s.sharedWithUserId = :userId")
    void deleteByFileIdAndSharedWithUserId(@Param("fileId") String fileId, @Param("userId") String userId);

    /** Remove all shares when a file is deleted. */
    @Modifying
    @Query("DELETE FROM SharedFile s WHERE s.fileId = :fileId")
    void deleteAllByFileId(@Param("fileId") String fileId);
}
