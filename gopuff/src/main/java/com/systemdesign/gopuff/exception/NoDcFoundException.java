package com.systemdesign.gopuff.exception;

/**
 * Thrown when no distribution center serves the supplied coordinates (the location is
 * outside every DC's delivery radius). Maps to HTTP 422 Unprocessable Entity.
 */
public class NoDcFoundException extends RuntimeException {

    public NoDcFoundException(String message) {
        super(message);
    }
}
