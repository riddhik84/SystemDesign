package com.systemdesign.strava.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis cache for per-segment leaderboards.
 *
 * Key namespace:
 *   strava:leaderboard:{segmentId}   → ZSET (member=userId, score=elapsedTimeSeconds)
 *
 * The score is the athlete's fastest elapsed time on the segment, so a plain ascending
 * ZSET range yields the leaderboard fastest-first. Only an athlete's BEST effort is kept:
 * a new effort replaces the stored score only when it is faster (lower) than the current one.
 *
 * Graceful degradation: all Redis calls are wrapped in try/catch.
 * A Redis failure is a no-op / returns an empty list so the request stays alive
 * (callers fall back to the segment_efforts table in the primary datastore).
 */
@Service
public class LeaderboardCacheService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardCacheService.class);
    private static final String LEADERBOARD_PREFIX = "strava:leaderboard:";

    private final StringRedisTemplate redis;

    public LeaderboardCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * A single leaderboard entry read back from the ZSET: an athlete and their best elapsed time.
     */
    public static record Entry(String userId, long elapsedTimeSeconds) {
    }

    /**
     * Record an effort for an athlete on a segment, keeping only their BEST (fastest) time.
     * Reads the current score and only writes when the effort is new or strictly faster.
     * No-op on Redis failure.
     */
    public void addEffort(String segmentId, String userId, long elapsedTimeSeconds) {
        try {
            String key = LEADERBOARD_PREFIX + segmentId;
            Double current = redis.opsForZSet().score(key, userId);
            if (current == null || elapsedTimeSeconds < current) {
                redis.opsForZSet().add(key, userId, elapsedTimeSeconds);
            }
        } catch (Exception e) {
            log.warn("Leaderboard cache ADD failed segmentId={} userId={}: {}", segmentId, userId, e.getMessage());
        }
    }

    /**
     * Get the top {@code limit} entries for a segment, ascending by elapsed time (fastest first).
     * Returns an empty list on cache miss or Redis failure.
     */
    public List<Entry> top(String segmentId, int limit) {
        try {
            String key = LEADERBOARD_PREFIX + segmentId;
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    redis.opsForZSet().rangeWithScores(key, 0, limit - 1);
            if (tuples == null) return new ArrayList<>();
            List<Entry> entries = new ArrayList<>(tuples.size());
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                String userId = tuple.getValue();
                Double score = tuple.getScore();
                if (userId == null || score == null) continue;
                entries.add(new Entry(userId, (long) Math.round(score)));
            }
            return entries;
        } catch (Exception e) {
            log.warn("Leaderboard cache TOP failed segmentId={}: {}", segmentId, e.getMessage());
            return new ArrayList<>();
        }
    }
}
