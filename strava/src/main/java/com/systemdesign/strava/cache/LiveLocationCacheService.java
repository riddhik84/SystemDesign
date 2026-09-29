package com.systemdesign.strava.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.strava.dto.LiveLocationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis cache for the live location of an in-progress activity.
 *
 * Key namespace:
 *   strava:live:{activityId}   → LiveLocationResponse JSON (TTL: app.live.location-ttl-seconds)
 *
 * The phone is the source of truth while recording, so every GPS batch sync updates
 * this key with the latest known position + running aggregates. Friends watching a
 * live activity read straight from this cache, which keeps the "who is out on a ride
 * right now" query off the primary datastore and lets stale entries auto-expire via TTL.
 *
 * Graceful degradation: all Redis calls are wrapped in try/catch.
 * A Redis failure returns empty (cache miss) or is a no-op so the request stays alive
 * (callers fall back to the activity's persisted running-state fields).
 */
@Service
public class LiveLocationCacheService {

    private static final Logger log = LoggerFactory.getLogger(LiveLocationCacheService.class);
    private static final String LIVE_PREFIX = "strava:live:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${app.live.location-ttl-seconds:300}")
    private long locationTtlSeconds;

    public LiveLocationCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Cache the latest live location for an activity (SET with TTL).
     * No-op on Redis failure so a live-location update never breaks GPS ingestion.
     */
    public void updateLocation(String activityId, LiveLocationResponse location) {
        try {
            String json = objectMapper.writeValueAsString(location);
            redis.opsForValue().set(LIVE_PREFIX + activityId, json, Duration.ofSeconds(locationTtlSeconds));
        } catch (Exception e) {
            log.warn("Live location cache SET failed activityId={}: {}", activityId, e.getMessage());
        }
    }

    /**
     * Get the cached live location for an activity.
     * Returns empty on cache miss or Redis failure.
     */
    public Optional<LiveLocationResponse> getLocation(String activityId) {
        try {
            String json = redis.opsForValue().get(LIVE_PREFIX + activityId);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, LiveLocationResponse.class));
        } catch (Exception e) {
            log.warn("Live location cache GET failed activityId={}: {}", activityId, e.getMessage());
            return Optional.empty();
        }
    }
}
