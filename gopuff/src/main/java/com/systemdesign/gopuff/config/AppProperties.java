package com.systemdesign.gopuff.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed application configuration bound from the {@code app.*} prefix
 * in {@code application.yml}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Dc dc = new Dc();
    private final Cache cache = new Cache();

    /** Public base URL of the service (used in docs / links). */
    private String baseUrl = "http://localhost:8080";

    @Getter
    @Setter
    public static class Dc {
        /**
         * Maximum straight-line distance (miles) between a user and a distribution
         * center for that DC to be considered able to deliver within the 1-hour
         * window. Defaults to 60 miles.
         */
        private double maxDistanceMiles = 60.0;
    }

    @Getter
    @Setter
    public static class Cache {
        /** TTL (seconds) for cached availability responses. Defaults to 60s. */
        private long availabilityTtlSeconds = 60;
    }
}
