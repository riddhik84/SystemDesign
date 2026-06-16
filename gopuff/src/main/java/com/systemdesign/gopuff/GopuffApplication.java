package com.systemdesign.gopuff;

import com.systemdesign.gopuff.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the GoPuff-like local delivery service.
 *
 * <p>This is a system design reference implementation. It exposes two endpoints:
 * <ul>
 *     <li>{@code GET /availability} — check which catalog items can be delivered to a
 *         user's location within the configured delivery radius. Served from a Redis
 *         cache (1-minute TTL) to meet the &lt;100ms latency SLA at 20K QPS.</li>
 *     <li>{@code POST /orders} — atomically purchase multiple items. The entire order is
 *         rejected if any item is unavailable (no partial fulfillment). Strong
 *         consistency is guaranteed via a SERIALIZABLE Postgres transaction combined
 *         with {@code SELECT ... FOR UPDATE} row locking.</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class GopuffApplication {

    public static void main(String[] args) {
        SpringApplication.run(GopuffApplication.class, args);
    }
}
