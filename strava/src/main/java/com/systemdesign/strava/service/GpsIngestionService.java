package com.systemdesign.strava.service;

import com.systemdesign.strava.cache.LiveLocationCacheService;
import com.systemdesign.strava.dto.ActivityStatsResponse;
import com.systemdesign.strava.dto.GpsBatchRequest;
import com.systemdesign.strava.dto.GpsPointDto;
import com.systemdesign.strava.dto.LiveLocationResponse;
import com.systemdesign.strava.model.Activity;
import com.systemdesign.strava.model.ActivityStatus;
import com.systemdesign.strava.model.RoutePoint;
import com.systemdesign.strava.repository.ActivityRepository;
import com.systemdesign.strava.repository.RoutePointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * GPS batch ingestion service — the offline-sync + running-aggregate core of the design.
 *
 * The phone is the source of truth while recording: it samples GPS, buffers points offline, and
 * syncs them to the server in BATCHES ordered by a client-assigned monotonic {@code sequence}.
 * This service appends the new points and folds each one into the activity's RUNNING aggregate
 * stats (distance, elapsed/moving time, elevation gain). Because ingestion is:
 *   - IDEMPOTENT: points with sequence &lt;= the highest applied sequence are skipped, so a client
 *     safely retrying a batch (flaky network, app restart) never double-counts; and
 *   - LIGHTWEIGHT: a small append + a handful of arithmetic ops, sharded per activity/user,
 * the write path scales to millions of concurrent activities with clients syncing at their own
 * cadence rather than streaming every GPS tick.
 */
@Service
public class GpsIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GpsIngestionService.class);

    private final ActivityRepository activityRepository;
    private final RoutePointRepository routePointRepository;
    private final StatsCalculator statsCalculator;
    private final LiveLocationCacheService liveLocationCacheService;

    /**
     * Minimum speed (meters/second) between two consecutive points for the interval to count as
     * "moving" time; slower intervals are treated as stopped (traffic lights, rest stops) and only
     * contribute to elapsed time.
     */
    @Value("${app.activity.moving-speed-threshold-mps:0.5}")
    private double movingSpeedThresholdMps;

    public GpsIngestionService(ActivityRepository activityRepository,
                               RoutePointRepository routePointRepository,
                               StatsCalculator statsCalculator,
                               LiveLocationCacheService liveLocationCacheService) {
        this.activityRepository = activityRepository;
        this.routePointRepository = routePointRepository;
        this.statsCalculator = statsCalculator;
        this.liveLocationCacheService = liveLocationCacheService;
    }

    /**
     * Ingest a batch of GPS points for an activity, appending points and updating running aggregates.
     *
     * @param activityId the target activity
     * @param batch      the batch of client-buffered GPS points (unordered by sequence is tolerated)
     * @return a snapshot of the activity's aggregate stats after applying the batch
     * @throws NoSuchElementException if the activity does not exist
     * @throws IllegalStateException  if the activity is already COMPLETED (no further points accepted)
     */
    @Transactional
    public ActivityStatsResponse ingestBatch(String activityId, GpsBatchRequest batch) {
        Activity activity = activityRepository.findById(activityId)
            .orElseThrow(() -> new NoSuchElementException("Activity not found: " + activityId));

        if (activity.getStatus() == ActivityStatus.COMPLETED) {
            throw new IllegalStateException("Cannot ingest points into a completed activity: " + activityId);
        }

        // Sort by the client-assigned monotonic sequence so out-of-order arrivals apply correctly.
        List<GpsPointDto> points = batch.getPoints().stream()
            .sorted(Comparator.comparingLong(GpsPointDto::getSequence))
            .toList();

        int applied = 0;
        GpsPointDto latestApplied = null;

        for (GpsPointDto point : points) {
            // IDEMPOTENCY GUARD: skip anything already applied (retried/duplicate batch).
            if (point.getSequence() <= activity.getLastSequence()) {
                continue;
            }

            // Persist the raw route point.
            RoutePoint routePoint = new RoutePoint();
            routePoint.setActivityId(activityId);
            routePoint.setSequence(point.getSequence());
            routePoint.setLatitude(point.getLatitude());
            routePoint.setLongitude(point.getLongitude());
            routePoint.setElevationMeters(point.getElevationMeters());
            routePoint.setRecordedAt(point.getRecordedAt());
            routePointRepository.save(routePoint);

            // Fold the point into the running aggregates relative to the previous point.
            if (activity.getLastLat() != null && activity.getLastLng() != null) {
                double d = statsCalculator.haversineMeters(
                    activity.getLastLat(), activity.getLastLng(),
                    point.getLatitude(), point.getLongitude());
                activity.setDistanceMeters(activity.getDistanceMeters() + d);

                if (activity.getLastPointTime() != null) {
                    long dtSeconds = Duration.between(activity.getLastPointTime(), point.getRecordedAt()).getSeconds();
                    if (dtSeconds > 0) {
                        activity.setElapsedTimeSeconds(activity.getElapsedTimeSeconds() + dtSeconds);
                        double speed = d / dtSeconds;
                        if (speed >= movingSpeedThresholdMps) {
                            activity.setMovingTimeSeconds(activity.getMovingTimeSeconds() + dtSeconds);
                        }
                    }
                }

                if (activity.getLastElevationMeters() != null) {
                    double elevationDelta = point.getElevationMeters() - activity.getLastElevationMeters();
                    if (elevationDelta > 0) {
                        activity.setElevationGainMeters(activity.getElevationGainMeters() + elevationDelta);
                    }
                }
            }

            // Advance the running-state cursor.
            activity.setLastLat(point.getLatitude());
            activity.setLastLng(point.getLongitude());
            activity.setLastElevationMeters(point.getElevationMeters());
            activity.setLastPointTime(point.getRecordedAt());
            activity.setPointCount(activity.getPointCount() + 1);
            activity.setLastSequence(point.getSequence());

            latestApplied = point;
            applied++;
        }

        // Recompute average speed from the accumulated moving time.
        activity.setAverageSpeedMps(
            activity.getMovingTimeSeconds() > 0
                ? activity.getDistanceMeters() / activity.getMovingTimeSeconds()
                : 0d);

        Activity saved = activityRepository.save(activity);

        // Best-effort live-location update for friends watching in real time (never fails the write).
        if (latestApplied != null) {
            LiveLocationResponse live = new LiveLocationResponse();
            live.setActivityId(saved.getId());
            live.setUserId(saved.getUserId());
            live.setLatitude(latestApplied.getLatitude());
            live.setLongitude(latestApplied.getLongitude());
            live.setDistanceMeters(saved.getDistanceMeters());
            live.setElapsedTimeSeconds(saved.getElapsedTimeSeconds());
            live.setUpdatedAt(LocalDateTime.now());
            liveLocationCacheService.updateLocation(saved.getId(), live);
        }

        log.info("Ingested batch for activity id={}: received={} applied={} lastSequence={} distanceMeters={}",
            activityId, points.size(), applied, saved.getLastSequence(), saved.getDistanceMeters());

        return toStats(saved);
    }

    /**
     * Build an ActivityStatsResponse snapshot from the current activity aggregates.
     */
    private ActivityStatsResponse toStats(Activity a) {
        ActivityStatsResponse stats = new ActivityStatsResponse();
        stats.setActivityId(a.getId());
        stats.setStatus(a.getStatus());
        stats.setElapsedTimeSeconds(a.getElapsedTimeSeconds());
        stats.setMovingTimeSeconds(a.getMovingTimeSeconds());
        stats.setDistanceMeters(a.getDistanceMeters());
        stats.setElevationGainMeters(a.getElevationGainMeters());
        stats.setAverageSpeedMps(a.getAverageSpeedMps());
        stats.setPointCount(a.getPointCount());
        stats.setLastSequence(a.getLastSequence());
        return stats;
    }
}
