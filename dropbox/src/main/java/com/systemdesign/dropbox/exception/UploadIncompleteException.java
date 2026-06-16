package com.systemdesign.dropbox.exception;

/**
 * Thrown when a client tries to download or operate on a file that has not
 * yet reached READY state — e.g., still UPLOADING or PENDING.
 * Maps to HTTP 409 (Conflict) in GlobalExceptionHandler.
 */
public class UploadIncompleteException extends RuntimeException {

    public UploadIncompleteException(String fileId) {
        super("File " + fileId + " is not yet ready — upload may still be in progress");
    }
}
