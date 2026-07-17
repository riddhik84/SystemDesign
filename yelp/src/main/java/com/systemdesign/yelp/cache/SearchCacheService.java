package com.systemdesign.yelp.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.yelp.dto.BusinessDetailResponse;
import com.systemdesign.yelp.dto.BusinessSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis cache for search results and business detail pages.
 *
 * Key namespaces:
 *   yelp:search:{cacheKey}   → BusinessSearchResponse JSON (TTL: 5 min)
 *   yelp:biz:{businessId}    → BusinessDetailResponse JSON (TTL: 10 min)
 *
 * Cache keys for search include rounded lat/lon (3 decimal places ≈ 111m precision).
 * Users within ~111m of each other share the same cache entry, significantly
 * increasing hit rate in dense urban areas.
 *
 * Graceful degradation: all Redis calls are wrapped in try/catch.
 * A Redis failure returns empty (cache miss) so the app stays alive.
 *
 * Invalidation:
 *   - Business detail cache is evicted when a new review is added (rating changes)
 *   - Search cache is NOT individually evicted (expensive to find which keys to delete)
 *     — instead we rely on 5-min TTL for eventual consistency of search results
 *   - This is an acceptable trade-off: stale search results for <5 min vs. complex
 *     tag-based invalidation
 */
@Service
public class SearchCacheService {

    private static final Logger log = LoggerFactory.getLogger(SearchCacheService.class);
    private static final String SEARCH_PREFIX = "yelp:search:";
    private static final String BIZ_PREFIX = "yelp:biz:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${app.cache.search-ttl-seconds:300}")
    private long searchTtlSeconds;

    @Value("${app.cache.business-ttl-seconds:600}")
    private long businessTtlSeconds;

    public SearchCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Optional<BusinessSearchResponse> get(String cacheKey) {
        try {
            String json = redis.opsForValue().get(SEARCH_PREFIX + cacheKey);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, BusinessSearchResponse.class));
        } catch (Exception e) {
            log.warn("Search cache GET failed key={}: {}", cacheKey, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String cacheKey, BusinessSearchResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redis.opsForValue().set(SEARCH_PREFIX + cacheKey, json, Duration.ofSeconds(searchTtlSeconds));
        } catch (Exception e) {
            log.warn("Search cache SET failed key={}: {}", cacheKey, e.getMessage());
        }
    }

    public Optional<BusinessDetailResponse> getBusinessDetail(String businessId) {
        try {
            String json = redis.opsForValue().get(BIZ_PREFIX + businessId);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, BusinessDetailResponse.class));
        } catch (Exception e) {
            log.warn("Business detail cache GET failed id={}: {}", businessId, e.getMessage());
            return Optional.empty();
        }
    }

    public void putBusinessDetail(String businessId, BusinessDetailResponse detail) {
        try {
            String json = objectMapper.writeValueAsString(detail);
            redis.opsForValue().set(BIZ_PREFIX + businessId, json, Duration.ofSeconds(businessTtlSeconds));
        } catch (Exception e) {
            log.warn("Business detail cache SET failed id={}: {}", businessId, e.getMessage());
        }
    }

    public void evictBusinessDetail(String businessId) {
        try {
            redis.delete(BIZ_PREFIX + businessId);
            log.debug("Evicted business detail cache for id={}", businessId);
        } catch (Exception e) {
            log.warn("Business detail cache DEL failed id={}: {}", businessId, e.getMessage());
        }
    }
}
