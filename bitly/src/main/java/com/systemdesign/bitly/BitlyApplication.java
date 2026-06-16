package com.systemdesign.bitly;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Bitly URL Shortener service.
 *
 * <p>Architecture overview:
 * <ul>
 *   <li>Write path: Controller → UrlShorteningService → ShortCodeGenerator (Redis counter
 *       batching) → PostgreSQL</li>
 *   <li>Read path: Controller → UrlRedirectService → Redis Cache → PostgreSQL fallback</li>
 * </ul>
 *
 * <p>Design targets: 1B URLs, 100M DAU, 1000:1 read:write ratio, redirect latency &lt; 100ms,
 * 99.99% availability.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BitlyApplication {

    public static void main(String[] args) {
        SpringApplication.run(BitlyApplication.class, args);
    }
}
