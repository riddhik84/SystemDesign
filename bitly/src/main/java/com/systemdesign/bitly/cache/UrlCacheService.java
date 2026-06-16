package com.systemdesign.bitly.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed cache for the URL redirect hot path.
 *
 * <h2>Key design decisions</h2>
 * <ul>
 *   <li><strong>String values</strong> — we store the raw long URL string.  There is no need
 *       for JSON serialisation; this keeps memory usage in Redis minimal (a URL averages ~80 bytes
 *       vs ~200 bytes for a JSON-serialised object) and reads faster.</li>
 *   <li><strong>TTL alignment</strong> — when a URL has an explicit expiration date we compute
 *       the remaining time and use that as the Redis TTL.  This ensures the cache entry expires
 *       at (or before) the same wall-clock time as the database record, preventing stale cache
 *       hits for expired links without requiring a separate cache invalidation.</li>
 *   <li><strong>Cache-aside pattern</strong> — the service does not use Spring's {@code @Cacheable}
 *       annotation because we need fine-grained TTL control per entry.  Explicit get/put/evict
 *       methods make the behaviour clear and testable.</li>
 *   <li><strong>No negative caching</strong> — we deliberately do not cache "not found" results.
 *       The expected miss rate is very low (only for brand-new codes before the cache warms) and
 *       negative caching adds complexity (invalidation on insert) for negligible gain.</li>
 * </ul>
 *
 * <h2>Key namespace</h2>
 * <pre>
 *   url:{shortCode}  →  longUrl  (TTL = remaining time or default 24h)
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrlCacheService {

    /**
     * Prefix for all URL cache keys.  Keeping a prefix makes it easy to flush all URL cache
     * entries independently (e.g. {@code SCAN url:*} + DEL) and avoids collision with the
     * counter key used by {@code ShortCodeGenerator} ({@code url:counter}).
     */
    static final String KEY_PREFIX = "url:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Retrieve the long URL for the given short code from Redis.
     *
     * @param shortCode the 8-character (or alias) short code
     * @return an {@link Optional} containing the long URL, or empty on cache miss
     */
    public Optional<String> get(String shortCode) {
        try {
            String value = redisTemplate.opsForValue().get(buildKey(shortCode));
            if (value != null) {
                log.debug("Cache HIT for shortCode={}", shortCode);
            } else {
                log.debug("Cache MISS for shortCode={}", shortCode);
            }
            return Optional.ofNullable(value);
        } catch (Exception e) {
            // Redis is unavailable — degrade gracefully to DB fallback.
            // We log a warning (not error) because this is a cache layer, not the source of truth.
            log.warn("Redis GET failed for shortCode={}, falling back to DB: {}", shortCode, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Store a long URL in Redis with the given TTL.
     *
     * @param shortCode the short code to use as the cache key
     * @param longUrl   the original URL to cache
     * @param ttl       how long to retain this entry; must be positive
     */
    public void put(String shortCode, String longUrl, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(buildKey(shortCode), longUrl, ttl);
            log.debug("Cache SET shortCode={}, ttl={}", shortCode, ttl);
        } catch (Exception e) {
            // Non-fatal: the URL can still be served from the DB on the next request.
            log.warn("Redis SET failed for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    /**
     * Remove a cached entry.  Called by the write path when an existing URL is updated or
     * explicitly invalidated so the next read fetches fresh data from the DB.
     *
     * @param shortCode the short code whose cache entry should be removed
     */
    public void evict(String shortCode) {
        try {
            Boolean deleted = redisTemplate.delete(buildKey(shortCode));
            log.debug("Cache EVICT shortCode={}, deleted={}", shortCode, deleted);
        } catch (Exception e) {
            log.warn("Redis DELETE failed for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    /**
     * Build the Redis key for a given short code.
     * Example: {@code "url:0a3Zk8mQ"}.
     */
    private String buildKey(String shortCode) {
        return KEY_PREFIX + shortCode;
    }
}
