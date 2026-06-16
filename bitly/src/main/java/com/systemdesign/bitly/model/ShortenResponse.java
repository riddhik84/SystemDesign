package com.systemdesign.bitly.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO returned by {@code POST /urls} with HTTP 201 Created.
 *
 * <p>Clients should store {@code short_url} and use it for redirects. The {@code short_code}
 * field is included so callers can construct CDN or preview URLs without string-parsing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of a successful URL shortening request")
public class ShortenResponse {

    @JsonProperty("short_url")
    @Schema(
        description = "The fully-qualified shortened URL",
        example = "http://localhost:8080/0a3Zk8mQ"
    )
    private String shortUrl;

    @JsonProperty("short_code")
    @Schema(
        description = "Just the short code segment",
        example = "0a3Zk8mQ"
    )
    private String shortCode;

    @JsonProperty("long_url")
    @Schema(
        description = "The original URL that was shortened",
        example = "https://www.example.com/some/very/long/path"
    )
    private String longUrl;

    @JsonProperty("expiration_date")
    @Schema(
        description = "Expiry instant, or null if the link never expires",
        example = "2025-12-31T23:59:59"
    )
    private LocalDateTime expirationDate;

    @JsonProperty("created_at")
    @Schema(description = "Creation timestamp")
    private LocalDateTime createdAt;
}
