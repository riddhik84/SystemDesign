package com.systemdesign.dropbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis configuration for pub/sub change notification.
 *
 * StringRedisTemplate is used for all publish operations (serializes to plain String/JSON).
 *
 * RedisMessageListenerContainer is the backbone for subscriber registration.
 * Each connected WebSocket session registers a ChannelTopic on this container
 * and removes it on disconnect — see SyncWebSocketHandler for details.
 *
 * Channel naming convention: "file-changes:{userId}"
 * This keeps each user's change stream isolated and allows selective fan-out.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }
}
