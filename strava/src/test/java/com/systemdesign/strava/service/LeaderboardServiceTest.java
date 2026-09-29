package com.systemdesign.strava.service;

import com.systemdesign.strava.cache.LeaderboardCacheService;
import com.systemdesign.strava.dto.LeaderboardResponse;
import com.systemdesign.strava.model.ActivityType;
import com.systemdesign.strava.model.Segment;
import com.systemdesign.strava.model.SegmentEffort;
import com.systemdesign.strava.repository.ActivityRepository;
import com.systemdesign.strava.repository.RoutePointRepository;
import com.systemdesign.strava.repository.SegmentEffortRepository;
import com.systemdesign.strava.repository.SegmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * LeaderboardServiceTest: pure Mockito unit tests for the read-time leaderboard, focused on
 * the DB fallback path taken when the Redis sorted-set cache is empty (e.g. after a cache
 * flush or cold start).
 *
 * Verifies that the fallback reads efforts ordered ascending by elapsed time, deduplicates by
 * user keeping their best (fastest) effort, and assigns ranks 1..N.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaderboardServiceTest {

    @Mock
    private SegmentRepository segmentRepository;

    @Mock
    private RoutePointRepository routePointRepository;

    @Mock
    private SegmentEffortRepository segmentEffortRepository;

    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private LeaderboardCacheService leaderboardCacheService;

    @Mock
    private UserService userService;

    @Spy
    private StatsCalculator statsCalculator = new StatsCalculator();

    @InjectMocks
    private LeaderboardService leaderboardService;

    @Test
    void cacheMissFallsBackToDbSortedDedupedAndRanked() {
        Segment segment = new Segment();
        segment.setId("s1");
        segment.setName("Hawk Hill Climb");
        segment.setActivityType(ActivityType.RIDE);
        when(segmentRepository.findById("s1")).thenReturn(Optional.of(segment));

        // Cache empty -> DB fallback.
        when(leaderboardCacheService.top(eq("s1"), anyInt())).thenReturn(List.of());

        // Ascending by elapsed time; user u1 appears twice (100 best, 150 worse).
        List<SegmentEffort> efforts = List.of(
            effort("e1", "u1", 100),
            effort("e2", "u2", 120),
            effort("e3", "u1", 150),
            effort("e4", "u3", 200)
        );
        when(segmentEffortRepository.findBySegmentIdOrderByElapsedTimeSecondsAsc(
            eq("s1"), any(Pageable.class))).thenReturn(efforts);

        when(userService.resolveName(anyString())).thenAnswer(inv -> "name-" + inv.getArgument(0));

        LeaderboardResponse response = leaderboardService.getLeaderboard("s1", 10);

        assertThat(response.getSegmentId()).isEqualTo("s1");
        assertThat(response.getSegmentName()).isEqualTo("Hawk Hill Climb");
        assertThat(response.getEntries()).hasSize(3);

        // Deduped by user keeping best, ordered ascending by time, ranks 1..N.
        assertThat(response.getEntries().get(0).getRank()).isEqualTo(1);
        assertThat(response.getEntries().get(0).getUserId()).isEqualTo("u1");
        assertThat(response.getEntries().get(0).getElapsedTimeSeconds()).isEqualTo(100L);

        assertThat(response.getEntries().get(1).getRank()).isEqualTo(2);
        assertThat(response.getEntries().get(1).getUserId()).isEqualTo("u2");
        assertThat(response.getEntries().get(1).getElapsedTimeSeconds()).isEqualTo(120L);

        assertThat(response.getEntries().get(2).getRank()).isEqualTo(3);
        assertThat(response.getEntries().get(2).getUserId()).isEqualTo("u3");
        assertThat(response.getEntries().get(2).getElapsedTimeSeconds()).isEqualTo(200L);
    }

    private SegmentEffort effort(String id, String userId, long elapsedSeconds) {
        SegmentEffort effort = new SegmentEffort();
        effort.setId(id);
        effort.setSegmentId("s1");
        effort.setActivityId("act-" + id);
        effort.setUserId(userId);
        effort.setElapsedTimeSeconds(elapsedSeconds);
        effort.setAchievedAt(LocalDateTime.now());
        return effort;
    }
}
