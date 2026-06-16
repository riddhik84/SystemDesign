package com.systemdesign.gopuff.exception;

/**
 * Thrown when at least one requested item cannot be fulfilled (out of stock, or no single
 * DC can satisfy the entire order). Maps to HTTP 409 Conflict.
 */
public class ItemUnavailableException extends RuntimeException {

    public ItemUnavailableException(String message) {
        super(message);
    }
}
