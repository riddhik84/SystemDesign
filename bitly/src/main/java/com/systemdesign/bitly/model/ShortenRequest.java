package com.systemdesign.bitly.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDateTime;

/**
 * Request DTO for {@code POST /urls}.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code long_url} (required) — the URL to shorten; validated as a well-formed URL.</li>
 *   <li>{@code custom_alias} (optional) — alphanumeric slug; 3–100 chars; must be unique.</li>
 *   <li>{@code expiration_date} (optional) — ISO-8601 datetime after which the link expires.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload for creating a new shortened URL")
public class ShortenRequest {

    @NotBlank(message = "long_url must not be blank")
    @URL(message = "long_url must be a valid URL (include scheme, e.g. https://)")
    @JsonProperty("long_url")
    @Schema(
        description = "The original URL to shorten",
        example = "https://www.example.com/some/very/long/path?with=query&params=true",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String longUrl;

    /**
     * Optional human-readable alias. Allowed characters: letters, digits, hyphens, underscores.
     * Must be unique across all existing short codes.
     */
    @Size(min = 3, max = 100, message = "custom_alias must be between 3 and 100 characters")
    @Pattern(
        regexp = "^[a-zA-Z0-9_-]+$",
        message = "custom_alias may only contain letters, digits, hyphens, and underscores"
    )
    @JsonProperty("custom_alias")
    @Schema(
        description = "Optional custom alias (letters, digits, hyphens, underscores; 3–100 chars)",
        example = "my-promo-link",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    )
    private String customAlias;

    @JsonProperty("expiration_date")
    @Schema(
        description = "Optional expiry date-time in ISO-8601 format. After this instant the link returns 410 Gone.",
        example = "2025-12-31T23:59:59",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    )
    private LocalDateTime expirationDate;
}
