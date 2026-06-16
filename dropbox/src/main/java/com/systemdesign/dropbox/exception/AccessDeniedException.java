package com.systemdesign.dropbox.exception;

/**
 * Thrown when a user attempts to access or modify a file they don't own and
 * have not been granted access to.
 * Maps to HTTP 403 in GlobalExceptionHandler.
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }

    public AccessDeniedException(String userId, String fileId) {
        super("User " + userId + " does not have access to file " + fileId);
    }
}
