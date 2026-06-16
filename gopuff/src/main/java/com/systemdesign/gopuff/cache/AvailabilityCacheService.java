package com.systemdesign.gopuff.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.gopuff.config.AppProperties;
import com.systemdesign.gopuff.model.AvailabilityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed cache for availability responses.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Key layout: {@code avail:{lat}:{lon}:{items}:{page}}. The {@code AvailabilityService}
 *       builds the {@code {lat}:{lon}:{items}:{page}} portion; this class prepends the
 *       {@code avail:} namespace.</li>
 *   <li>Values are JSON (via Jackson), so cache entries survive DTO evolution and are
 *       inspectable with {@code redis-cli GET}.</li>
 *   <li><b>Graceful degradation:</b> every Redis call is wrapped in try/catch. If Redis is
 *       down, reads return {@link Optional#empty()} (treated as a cache miss → falls through
 *       to the DB) and writes/evicts become no-ops. The service stays available; it just
 *       loses the latency/QPS benefit of the cache.</li>
 * </ul>
 */
@Service
public class AvailabilityCacheService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityCacheService.class);
    private static final String KEY_PREFIX = "avail:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public AvailabilityCacheService(StringRedisTemplate redis,
                                    ObjectMapper objectMapper,
                                    AppProperties appProperties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    /** Look up a cached response. Returns empty on miss or any Redis failure. */
    public Optional<AvailabilityResponse> get(String cacheKey) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + cacheKey);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, AvailabilityResponse.class));
        } catch (Exception e) {
            log.warn("Redis GET failed for key {} ({}); treating as cache miss",
                    cacheKey, e.getMessage());
            return Optional.empty();
        }
    }

    /** Store a response with the configured TTL. No-op on Redis failure. */
    public void put(String cacheKey, AvailabilityResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            Duration ttl = Duration.ofSeconds(appProperties.getCache().getAvailabilityTtlSeconds());
            redis.opsForValue().set(KEY_PREFIX + cacheKey, json, ttl);
        } catch (Exception e) {
            log.warn("Redis SET failed for key {} ({}); skipping cache write",
                    cacheKey, e.getMessage());
        }
    }

    /** Delete a single cache entry. No-op on Redis failure. */
    public void evict(String cacheKey) {
        try {
            redis.delete(KEY_PREFIX + cacheKey);
        } catch (Exception e) {
            log.warn("Redis DEL failed for key {} ({})", cacheKey, e.getMessage());
        }
    }

    /**
     * Invalidate availability entries after an order changes stock at a DC.
     *
     * <p>This reference implementation uses a {@code SCAN} over the {@code avail:*} keyspace
     * and deletes every availability entry. That is intentionally conservative: cache keys
     * are coordinate-based, not DC-based, so we cannot precisely target only the keys that
     * touched {@code dcId} without a secondary index. In production you would maintain a
     * tag set (e.g. a Redis set {@code dc:{dcId}:keys}) and delete exactly the tagged keys,
     * or rely on the short 60s TTL. {@code dcId} is accepted here to document that intent
     * and to allow a future targeted implementation without changing callers.
     *
     * <p>{@code SCAN} is cursor-based and non-blocking, unlike {@code KEYS}, so it is safe to
     * run against a live Redis.
     */
    public void evictByDcId(String dcId) {
        try (Cursor<byte[]> cursor = redis.executeWithStickyConnection(
                connection -> connection.keyCommands().scan(
                        ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(500).build()))) {
            if (cursor == null) {
                return;
            }
            List<byte[]> toDelete = new ArrayList<>();
            while (cursor.hasNext()) {
                toDelete.add(cursor.next());
            }
            if (!toDelete.isEmpty()) {
                redis.executeWithStickyConnection(connection -> {
                    connection.keyCommands().del(toDelete.toArray(new byte[0][]));
                    return null;
                });
                log.debug("Evicted {} availability cache entries after stock change at DC {}",
                        toDelete.size(), dcId);
            }
        } catch (Exception e) {
            log.warn("Redis SCAN/DEL eviction failed for DC {} ({}); relying on TTL",
                    dcId, e.getMessage());
        }
    }
}
