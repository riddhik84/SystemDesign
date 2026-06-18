package com.systemdesign.ticketmaster.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisLockService {

    private final RedisTemplate<String, String> redisTemplate;

    public <T> T executeWithLock(String lockKey, int timeoutSeconds, Supplier<T> task) {
        String lockValue = UUID.randomUUID().toString();
        boolean acquired = false;

        try {
            acquired = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    lockValue,
                    Duration.ofSeconds(timeoutSeconds)
                )
            );

            if (!acquired) {
                throw new IllegalStateException("Could not acquire lock. Please try again.");
            }

            return task.get();

        } finally {
            if (acquired) {
                String currentValue = redisTemplate.opsForValue().get(lockKey);
                if (lockValue.equals(currentValue)) {
                    redisTemplate.delete(lockKey);
                }
            }
        }
    }
}
