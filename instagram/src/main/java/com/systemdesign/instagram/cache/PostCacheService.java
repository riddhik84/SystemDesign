package com.systemdesign.instagram.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.instagram.dto.PostResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis cache for individual post responses.
 *
 * Key namespace:
 *   insta:post:{postId}   → PostResponse JSON (TTL: 1 hour)
 *
 * Cache-aside pattern: read-through cache for post detail lookups.
 * When a post is created, it is immediately cached.
 * Posts are evicted on explicit evict() calls (e.g., if edited or deleted).
 *
 * Graceful degradation: all Redis calls are wrapped in try/catch.
 * A Redis failure returns empty (cache miss) so the app stays alive.
 */
@Service
public class PostCacheService {

    private static final Logger log = LoggerFactory.getLogger(PostCacheService.class);
    private static final String POST_PREFIX = "insta:post:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${app.cache.post-ttl-seconds:3600}")
    private long postTtlSeconds;

    public PostCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Get a cached post response by ID.
     * Returns empty on cache miss or Redis failure.
     */
    public Optional<PostResponse> get(String postId) {
        try {
            String json = redis.opsForValue().get(POST_PREFIX + postId);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, PostResponse.class));
        } catch (Exception e) {
            log.warn("Post cache GET failed postId={}: {}", postId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Cache a post response with TTL.
     */
    public void put(PostResponse post) {
        try {
            String json = objectMapper.writeValueAsString(post);
            redis.opsForValue().set(POST_PREFIX + post.getId(), json, Duration.ofSeconds(postTtlSeconds));
        } catch (Exception e) {
            log.warn("Post cache SET failed postId={}: {}", post.getId(), e.getMessage());
        }
    }

    /**
     * Evict a post from cache (e.g., on deletion or edit).
     */
    public void evict(String postId) {
        try {
            redis.delete(POST_PREFIX + postId);
            log.debug("Evicted post cache for postId={}", postId);
        } catch (Exception e) {
            log.warn("Post cache DEL failed postId={}: {}", postId, e.getMessage());
        }
    }
}
