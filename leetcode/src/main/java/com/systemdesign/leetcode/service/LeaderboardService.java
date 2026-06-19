package com.systemdesign.leetcode.service;

import com.systemdesign.leetcode.model.LeaderboardEntry;
import com.systemdesign.leetcode.repository.SubmissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaderboardService {
    private final RedisTemplate<String, String> redisTemplate;
    private final SubmissionRepository submissionRepository;

    private static final String LEADERBOARD_KEY_PREFIX = "competition:leaderboard:";
    private static final String USER_DATA_KEY_PREFIX = "competition:user:";

    public void updateLeaderboard(String competitionId, String userId, LocalDateTime completionTime) {
        String leaderboardKey = LEADERBOARD_KEY_PREFIX + competitionId;
        String userDataKey = USER_DATA_KEY_PREFIX + competitionId + ":" + userId;

        Integer problemsSolved = submissionRepository
                .countSolvedProblemsByUserInCompetition(userId, competitionId);

        Long totalTimeMs = calculateTotalTime(userId, competitionId, completionTime);

        double score = calculateScore(problemsSolved, totalTimeMs);

        ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
        zSetOps.add(leaderboardKey, userId, -score);

        redisTemplate.opsForHash().put(userDataKey, "problemsSolved", problemsSolved.toString());
        redisTemplate.opsForHash().put(userDataKey, "totalTimeMs", totalTimeMs.toString());
        redisTemplate.opsForHash().put(userDataKey, "score", String.valueOf(score));

        redisTemplate.expire(leaderboardKey, Duration.ofDays(7));
        redisTemplate.expire(userDataKey, Duration.ofDays(7));

        log.info("Updated leaderboard for competition {} - User: {}, Problems: {}, Time: {}ms, Score: {}",
                competitionId, userId, problemsSolved, totalTimeMs, score);
    }

    public List<LeaderboardEntry> getLeaderboard(String competitionId, int page, int limit) {
        String leaderboardKey = LEADERBOARD_KEY_PREFIX + competitionId;

        int start = (page - 1) * limit;
        int end = start + limit - 1;

        ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> topUsers = zSetOps.rangeWithScores(leaderboardKey, start, end);

        if (topUsers == null || topUsers.isEmpty()) {
            return new ArrayList<>();
        }

        List<LeaderboardEntry> leaderboard = new ArrayList<>();
        int rank = start + 1;

        for (ZSetOperations.TypedTuple<String> tuple : topUsers) {
            String userId = tuple.getValue();
            Double score = tuple.getScore();

            if (userId != null && score != null) {
                String userDataKey = USER_DATA_KEY_PREFIX + competitionId + ":" + userId;

                String problemsSolvedStr = (String) redisTemplate.opsForHash().get(userDataKey, "problemsSolved");
                String totalTimeMsStr = (String) redisTemplate.opsForHash().get(userDataKey, "totalTimeMs");

                LeaderboardEntry entry = LeaderboardEntry.builder()
                        .userId(userId)
                        .username("User" + userId.substring(0, 8))
                        .rank(rank)
                        .problemsSolved(problemsSolvedStr != null ? Integer.parseInt(problemsSolvedStr) : 0)
                        .totalTimeMs(totalTimeMsStr != null ? Long.parseLong(totalTimeMsStr) : 0L)
                        .score(Math.abs(score))
                        .build();

                leaderboard.add(entry);
                rank++;
            }
        }

        return leaderboard;
    }

    private Long calculateTotalTime(String userId, String competitionId, LocalDateTime lastCompletionTime) {
        return Duration.between(
                LocalDateTime.now().minusHours(2),
                lastCompletionTime
        ).toMillis();
    }

    private double calculateScore(Integer problemsSolved, Long totalTimeMs) {
        return (problemsSolved * 1000000.0) - (totalTimeMs / 1000.0);
    }

    public void clearLeaderboard(String competitionId) {
        String leaderboardKey = LEADERBOARD_KEY_PREFIX + competitionId;
        redisTemplate.delete(leaderboardKey);
    }
}
