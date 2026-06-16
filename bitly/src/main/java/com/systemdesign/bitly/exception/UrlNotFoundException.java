package com.systemdesign.bitly.exception;

/**
 * Thrown when a short code does not match any record in the database.
 * The controller maps this to HTTP 404 Not Found.
 */
public class UrlNotFoundException extends RuntimeException {

    private final String shortCode;

    public UrlNotFoundException(String shortCode) {
        super("No URL found for short code: " + shortCode);
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}
