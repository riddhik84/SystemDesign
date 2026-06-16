package com.systemdesign.dropbox.model;

/**
 * Lifecycle state of a file upload.
 *
 * State machine:
 *   PENDING   → UPLOADING  (multipart initiated, presigned URLs returned to client)
 *   UPLOADING → READY      (all chunks confirmed, S3 multipart completed)
 *   READY     → DELETED    (owner deletes the file)
 *   UPLOADING → DELETED    (upload aborted)
 */
public enum FileStatus {
    PENDING,
    UPLOADING,
    READY,
    DELETED
}
