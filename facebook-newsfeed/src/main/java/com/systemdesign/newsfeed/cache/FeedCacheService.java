package com.systemdesign.newsfeed.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Redis cache for user feeds (fanout-on-write).
 *
 * Key namespace:
 *   feed:{userId}   → ZSET (member=postId, score=createdAt epoch millis)
 *
 * Each user's feed is stored as a sorted set ordered by post creation time.
 * When a post is created, it is fanned out to all followers' feeds (for non-celebrity users).
 * The ZSET is trimmed to keep only the newest maxCachedFeed posts.
 *
 * Graceful degradation: all Redis calls are wrapped in try/catch.
 * A Redis failure returns empty/no-op so the app stays alive (falls back to DB fanout-on-read).
 */
@Service
public class FeedCacheService {

    private static final Logger log = LoggerFactory.getLogger(FeedCacheService.class);
    private static final String FEED_PREFIX = "feed:";

    private final StringRedisTemplate redis;

    @Value("${app.cache.feed-ttl-seconds:86400}")
    private long feedTtlSeconds;

    @Value("${app.feed.max-cached-feed:1000}")
    private int maxCachedFeed;

    public FeedCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Add a post to a user's feed (ZADD with score = createdAt epoch millis).
     * Trims the feed to keep only the newest maxCachedFeed posts.
     * Sets TTL on the feed key.
     */
    public void addToFeed(String userId, String postId, long scoreMs) {
        try {
            String key = FEED_PREFIX + userId;
            redis.opsForZSet().add(key, postId, scoreMs);

            // Trim to keep only the newest maxCachedFeed posts.
            // ZREMRANGEBYRANK removes elements with rank [0, size - maxCachedFeed - 1]
            // which keeps the top maxCachedFeed highest-scored (newest) elements.
            Long feedSize = redis.opsForZSet().zCard(key);
            if (feedSize != null && feedSize > maxCachedFeed) {
                redis.opsForZSet().removeRange(key, 0, feedSize - maxCachedFeed - 1);
            }

            redis.expire(key, Duration.ofSeconds(feedTtlSeconds));
        } catch (Exception e) {
            log.warn("Feed cache ADD failed userId={} postId={}: {}", userId, postId, e.getMessage());
        }
    }

    /**
     * Add a post to multiple users' feeds (fanout).
     */
    public void addToManyFeeds(Collection<String> userIds, String postId, long scoreMs) {
        for (String userId : userIds) {
            addToFeed(userId, postId, scoreMs);
        }
    }

    /**
     * Get a page of post IDs from a user's feed (ZREVRANGE for reverse-chronological order).
     * Returns empty list on cache miss or Redis failure.
     */
    public List<String> getFeedPostIds(String userId, int offset, int limit) {
        try {
            String key = FEED_PREFIX + userId;
            Set<String> postIds = redis.opsForZSet().reverseRange(key, offset, offset + limit - 1);
            if (postIds == null) return new ArrayList<>();
            return new ArrayList<>(postIds);
        } catch (Exception e) {
            log.warn("Feed cache GET failed userId={}: {}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get the size of a user's feed (ZCARD).
     * Returns 0 on cache miss or Redis failure.
     */
    public long feedSize(String userId) {
        try {
            String key = FEED_PREFIX + userId;
            Long size = redis.opsForZSet().zCard(key);
            return size != null ? size : 0L;
        } catch (Exception e) {
            log.warn("Feed cache SIZE failed userId={}: {}", userId, e.getMessage());
            return 0L;
        }
    }
}
