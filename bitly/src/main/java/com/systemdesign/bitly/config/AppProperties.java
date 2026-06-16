package com.systemdesign.bitly.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe binding for the {@code app.*} configuration namespace.
 *
 * <p>Validated at application startup so misconfiguration fails fast rather than silently
 * producing wrong behaviour at runtime.
 */
@ConfigurationProperties(prefix = "app")
@Validated
@Getter
@Setter
public class AppProperties {

    /** Base URL prepended to short codes in responses, e.g. {@code https://bit.ly}. */
    @NotBlank
    private String baseUrl;

    private Cache cache = new Cache();

    @Getter
    @Setter
    public static class Cache {
        /** Default Redis TTL (seconds) when a URL has no expiration date. */
        @Min(1)
        private long defaultTtlSeconds = 86_400L; // 24 hours
    }
}
