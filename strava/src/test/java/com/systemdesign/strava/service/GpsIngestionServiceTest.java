package com.systemdesign.strava.service;

import com.systemdesign.strava.dto.ActivityStatsResponse;
import com.systemdesign.strava.dto.GpsBatchRequest;
import com.systemdesign.strava.dto.GpsPointDto;
import com.systemdesign.strava.model.Activity;
import com.systemdesign.strava.model.ActivityStatus;
import com.systemdesign.strava.model.ActivityType;
import com.systemdesign.strava.model.RoutePoint;
import com.systemdesign.strava.repository.ActivityRepository;
import com.systemdesign.strava.repository.RoutePointRepository;
import com.systemdesign.strava.cache.LiveLocationCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * GpsIngestionServiceTest: pure Mockito unit tests for the offline-sync + running-aggregate
 * core of the write path.
 *
 * A real {@link StatsCalculator} is used (via {@code @Spy}) so distances come from the true
 * geodesic formula; the repositories and the live-location cache are mocked so no DB or Redis
 * is required.
 *
 * Verifies:
 *  - an ordered batch accumulates distance and moving time,
 *  - re-submitting the same batch is idempotent (sequences {@code <=} lastSequence are skipped),
 *  - samples slower than the moving-speed threshold add elapsed time but no moving time.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GpsIngestionServiceTest {

    @Mock
    private ActivityRepository activityRepository;

    @Mock
    private RoutePointRepository routePointRepository;

    @Mock
    private LiveLocationCacheService liveLocationCacheService;

    @Spy
    private StatsCalculator statsCalculator = new StatsCalculator();

    @InjectMocks
    private GpsIngestionService gpsIngestionService;

    @BeforeEach
    void setUp() {
        // @Value("${app.activity.moving-speed-threshold-mps}") field is not populated without a
        // Spring context, so set it explicitly for the below-threshold test.
        ReflectionTestUtils.setField(gpsIngestionService, "movingSpeedThresholdMps", 0.5);

        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(routePointRepository.save(any(RoutePoint.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void orderedBatchAccumulatesDistanceAndMovingTime() {
        Activity activity = newActivity();
        when(activityRepository.findById("a1")).thenReturn(Optional.of(activity));

        // ~100 m apart per hop, 10 s apart -> ~10 m/s, well above the 0.5 m/s threshold.
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
        GpsBatchRequest batch = batchOf(
            point(1, 37.7749, -122.4194, 10.0, t0),
            point(2, 37.7758, -122.4194, 12.0, t0.plusSeconds(10)),
            point(3, 37.7767, -122.4194, 15.0, t0.plusSeconds(20))
        );

        ActivityStatsResponse stats = gpsIngestionService.ingestBatch("a1", batch);

        assertThat(stats.getPointCount()).isEqualTo(3);
        assertThat(stats.getLastSequence()).isEqualTo(3);
        assertThat(stats.getDistanceMeters()).isGreaterThan(0.0);
        assertThat(stats.getMovingTimeSeconds()).isGreaterThan(0L);
        assertThat(stats.getElapsedTimeSeconds()).isGreaterThan(0L);
    }

    @Test
    void resubmittingSameBatchIsIdempotent() {
        Activity activity = newActivity();
        when(activityRepository.findById("a1")).thenReturn(Optional.of(activity));

        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
        GpsBatchRequest batch = batchOf(
            point(1, 37.7749, -122.4194, 10.0, t0),
            point(2, 37.7758, -122.4194, 12.0, t0.plusSeconds(10)),
            point(3, 37.7767, -122.4194, 15.0, t0.plusSeconds(20))
        );

        ActivityStatsResponse first = gpsIngestionService.ingestBatch("a1", batch);
        // Same activity instance (already advanced to lastSequence=3) is returned again.
        ActivityStatsResponse second = gpsIngestionService.ingestBatch("a1", batch);

        assertThat(second.getPointCount()).isEqualTo(first.getPointCount());
        assertThat(second.getLastSequence()).isEqualTo(first.getLastSequence());
        assertThat(second.getDistanceMeters()).isEqualTo(first.getDistanceMeters());
        assertThat(second.getMovingTimeSeconds()).isEqualTo(first.getMovingTimeSeconds());
    }

    @Test
    void samplesBelowMovingThresholdAddNoMovingTime() {
        Activity activity = newActivity();
        when(activityRepository.findById("a1")).thenReturn(Optional.of(activity));

        // ~4 m apart over 100 s -> ~0.04 m/s, below the 0.5 m/s threshold: elapsed time grows
        // but moving time must stay zero.
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
        GpsBatchRequest batch = batchOf(
            point(1, 37.7749, -122.4194, 10.0, t0),
            point(2, 37.77494, -122.4194, 10.0, t0.plusSeconds(100))
        );

        ActivityStatsResponse stats = gpsIngestionService.ingestBatch("a1", batch);

        assertThat(stats.getDistanceMeters()).isGreaterThan(0.0);
        assertThat(stats.getElapsedTimeSeconds()).isGreaterThan(0L);
        assertThat(stats.getMovingTimeSeconds()).isEqualTo(0L);
    }

    private Activity newActivity() {
        Activity activity = new Activity();
        activity.setId("a1");
        activity.setUserId("u1");
        activity.setType(ActivityType.RUN);
        activity.setStatus(ActivityStatus.ACTIVE);
        activity.setStartTime(LocalDateTime.of(2026, 1, 1, 8, 0, 0));
        return activity;
    }

    private GpsBatchRequest batchOf(GpsPointDto... points) {
        GpsBatchRequest batch = new GpsBatchRequest();
        batch.setPoints(new ArrayList<>(List.of(points)));
        return batch;
    }

    private GpsPointDto point(long sequence, double lat, double lng, double elevation, LocalDateTime recordedAt) {
        GpsPointDto dto = new GpsPointDto();
        dto.setSequence(sequence);
        dto.setLatitude(lat);
        dto.setLongitude(lng);
        dto.setElevationMeters(elevation);
        dto.setRecordedAt(recordedAt);
        return dto;
    }
}
