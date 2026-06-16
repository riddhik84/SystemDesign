package com.systemdesign.bitly.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration.
 *
 * <p>We use {@link StringRedisTemplate} throughout this service because all values stored in
 * Redis are plain strings (long URLs and counter values).  This avoids the overhead and
 * serialisation complexity of a generic {@code RedisTemplate<Object,Object>} and keeps
 * Redis keys human-readable for operational visibility.
 *
 * <p>Connection pool settings (Lettuce defaults) are intentionally left at defaults; tuning
 * should be done via {@code spring.data.redis.lettuce.pool.*} in application.yml for the
 * specific deployment environment.
 */
@Configuration
public class RedisConfig {

    /**
     * Primary template used for cache operations (URL lookups).
     * Using String serializers on both key and value side.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
