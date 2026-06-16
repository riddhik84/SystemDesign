package com.systemdesign.dropbox.exception;

/**
 * Thrown when a fileId does not exist in file_metadata or has been DELETED.
 * Maps to HTTP 404 in GlobalExceptionHandler.
 */
public class FileNotFoundException extends RuntimeException {

    public FileNotFoundException(String fileId) {
        super("File not found: " + fileId);
    }
}
