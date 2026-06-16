package com.systemdesign.bitly.exception;

import java.time.LocalDateTime;

/**
 * Thrown when a URL record exists but its {@code expiration_date} has passed.
 * The controller maps this to HTTP 410 Gone — semantically cleaner than 404 because the
 * resource existed but is no longer available. Using 410 also allows clients and CDNs to
 * cache the "gone" state and stop retrying.
 */
public class UrlExpiredException extends RuntimeException {

    private final String shortCode;
    private final LocalDateTime expiredAt;

    public UrlExpiredException(String shortCode, LocalDateTime expiredAt) {
        super("URL for short code '" + shortCode + "' expired at " + expiredAt);
        this.shortCode = shortCode;
        this.expiredAt = expiredAt;
    }

    public String getShortCode() {
        return shortCode;
    }

    public LocalDateTime getExpiredAt() {
        return expiredAt;
    }
}
