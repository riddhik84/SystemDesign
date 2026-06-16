package com.systemdesign.gopuff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis wiring for the availability cache.
 *
 * <p>We use a {@link StringRedisTemplate} and serialize {@code AvailabilityResponse}
 * objects to JSON ourselves (via Jackson in {@code AvailabilityCacheService}). This keeps
 * the cached payload human-readable and avoids Java-serialization class-version coupling,
 * which matters when the response DTO evolves across deploys.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
