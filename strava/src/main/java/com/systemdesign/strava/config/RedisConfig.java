package com.systemdesign.strava.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis wiring for the live-location cache and segment leaderboard ZSETs.
 *
 * <p>Everything the cache layer needs is string-based: live-location payloads are stored as
 * JSON strings (see {@code LiveLocationCacheService}) and leaderboards are Redis sorted sets
 * keyed by {@code userId} with the effort time as the score (see {@code LeaderboardCacheService}).
 * A {@link StringRedisTemplate} is therefore all that is required. The {@link ObjectMapper}
 * is JSR-310 aware so {@code LocalDateTime} fields serialize cleanly.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
