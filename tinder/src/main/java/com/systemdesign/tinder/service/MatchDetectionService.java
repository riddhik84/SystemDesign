package com.systemdesign.tinder.service;

import com.systemdesign.tinder.model.Swipe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchDetectionService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String SWIPE_KEY_PREFIX = "swipes:";
    private static final long TTL_HOURS = 24;

    private static final String LUA_SCRIPT = """
        local key = KEYS[1]
        local from_field = ARGV[1]
        local to_field = ARGV[2]
        local direction = ARGV[3]
        local ttl = tonumber(ARGV[4])

        redis.call('HSET', key, from_field, direction)
        redis.call('EXPIRE', key, ttl)

        local inverse_swipe = redis.call('HGET', key, to_field)

        if inverse_swipe == 'RIGHT' and direction == 'RIGHT' then
            return 1
        else
            return 0
        end
        """;

    public boolean recordSwipeAndCheckMatch(String swiperId, String targetUserId, Swipe.Direction direction) {
        String swipeKey = buildSwipeKey(swiperId, targetUserId);
        String swiperField = swiperId + "_swipe";
        String targetField = targetUserId + "_swipe";

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(LUA_SCRIPT);
        script.setResultType(Long.class);

        Long result = redisTemplate.execute(
            script,
            Collections.singletonList(swipeKey),
            swiperField,
            targetField,
            direction.name(),
            String.valueOf(TimeUnit.HOURS.toSeconds(TTL_HOURS))
        );

        boolean isMatch = result != null && result == 1L;

        log.debug("Swipe recorded: {} -> {} ({}). Match: {}",
            swiperId, targetUserId, direction, isMatch);

        return isMatch;
    }

    private String buildSwipeKey(String user1, String user2) {
        String sortedKey = user1.compareTo(user2) < 0
            ? user1 + ":" + user2
            : user2 + ":" + user1;
        return SWIPE_KEY_PREFIX + sortedKey;
    }

    public void cleanupSwipeData(String user1Id, String user2Id) {
        String key = buildSwipeKey(user1Id, user2Id);
        redisTemplate.delete(key);
        log.debug("Cleaned up swipe data for key: {}", key);
    }
}
