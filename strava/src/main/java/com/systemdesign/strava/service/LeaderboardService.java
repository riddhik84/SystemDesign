package com.systemdesign.strava.service;

import com.systemdesign.strava.cache.LeaderboardCacheService;
import com.systemdesign.strava.dto.LeaderboardEntry;
import com.systemdesign.strava.dto.LeaderboardResponse;
import com.systemdesign.strava.event.ActivityCompletedEvent;
import com.systemdesign.strava.model.Activity;
import com.systemdesign.strava.model.RoutePoint;
import com.systemdesign.strava.model.Segment;
import com.systemdesign.strava.model.SegmentEffort;
import com.systemdesign.strava.repository.ActivityRepository;
import com.systemdesign.strava.repository.RoutePointRepository;
import com.systemdesign.strava.repository.SegmentEffortRepository;
import com.systemdesign.strava.repository.SegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Segment leaderboard service: segment matching on activity completion, plus leaderboard reads.
 *
 * <p>A "segment" is a fixed stretch of road/trail. When an activity completes we replay its route
 * points and, for every segment of the matching {@link com.systemdesign.strava.model.ActivityType},
 * check whether the athlete passed through the segment's start and end (within a match radius). If so
 * we record a {@link SegmentEffort} and push the elapsed time into a Redis sorted-set leaderboard
 * (fastest first). Matching runs asynchronously AFTER the completing transaction commits — mirroring
 * the fanout-on-write pattern — so it never blocks the athlete's "stop activity" request.
 *
 * <p>Leaderboard reads are served from the Redis ZSET; on a cold cache (or Redis outage) we fall back
 * to the {@code segment_efforts} table (indexed on {@code (segment_id, elapsed_time_seconds)}),
 * de-duplicate to each athlete's best effort, and warm the cache for subsequent reads.
 */
@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    private final SegmentRepository segmentRepository;
    private final RoutePointRepository routePointRepository;
    private final SegmentEffortRepository segmentEffortRepository;
    private final ActivityRepository activityRepository;
    private final LeaderboardCacheService leaderboardCacheService;
    private final StatsCalculator statsCalculator;
    private final UserService userService;

    @Value("${app.leaderboard.default-limit:10}")
    private int defaultLimit;

    @Value("${app.segment.default-match-radius-meters:30}")
    private double defaultMatchRadiusMeters;

    public LeaderboardService(SegmentRepository segmentRepository,
                              RoutePointRepository routePointRepository,
                              SegmentEffortRepository segmentEffortRepository,
                              ActivityRepository activityRepository,
                              LeaderboardCacheService leaderboardCacheService,
                              StatsCalculator statsCalculator,
                              UserService userService) {
        this.segmentRepository = segmentRepository;
        this.routePointRepository = routePointRepository;
        this.segmentEffortRepository = segmentEffortRepository;
        this.activityRepository = activityRepository;
        this.leaderboardCacheService = leaderboardCacheService;
        this.statsCalculator = statsCalculator;
        this.userService = userService;
    }

    /**
     * Match a just-completed activity against all segments and record any efforts.
     *
     * <p>Runs asynchronously on a background thread AFTER the activity-completion transaction commits,
     * so the athlete's "stop" request returns immediately and this reads only committed data.
     *
     * @param event the activity-completed event carrying the activity and user ids
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void onActivityCompleted(ActivityCompletedEvent event) {
        Activity activity = activityRepository.findById(event.getActivityId()).orElse(null);
        if (activity == null) {
            log.warn("Segment matching skipped: activity id={} no longer exists", event.getActivityId());
            return;
        }

        List<RoutePoint> points = routePointRepository.findByActivityIdOrderBySequenceAsc(activity.getId());
        if (points.isEmpty()) {
            log.debug("Segment matching skipped: activity id={} has no route points", activity.getId());
            return;
        }

        List<Segment> segments = segmentRepository.findByActivityType(activity.getType());
        int matches = 0;
        for (Segment segment : segments) {
            double radius = segment.getMatchRadiusMeters() > 0
                ? segment.getMatchRadiusMeters() : defaultMatchRadiusMeters;

            // Find the FIRST route point within the match radius of the segment start.
            int startIdx = firstPointWithinRadius(points, 0, segment.getStartLat(), segment.getStartLng(), radius);
            if (startIdx < 0) {
                continue;
            }
            // Find a LATER route point within the match radius of the segment end.
            int endIdx = firstPointWithinRadius(points, startIdx + 1, segment.getEndLat(), segment.getEndLng(), radius);
            if (endIdx < 0) {
                continue;
            }

            RoutePoint start = points.get(startIdx);
            RoutePoint end = points.get(endIdx);
            if (!end.getRecordedAt().isAfter(start.getRecordedAt())) {
                continue;
            }

            long elapsed = Duration.between(start.getRecordedAt(), end.getRecordedAt()).getSeconds();

            SegmentEffort effort = new SegmentEffort();
            effort.setSegmentId(segment.getId());
            effort.setActivityId(activity.getId());
            effort.setUserId(activity.getUserId());
            effort.setElapsedTimeSeconds(elapsed);
            effort.setAchievedAt(LocalDateTime.now());
            segmentEffortRepository.save(effort);

            leaderboardCacheService.addEffort(segment.getId(), activity.getUserId(), elapsed);
            matches++;
            log.info("Segment match: activityId={} userId={} segmentId={} elapsed={}s",
                activity.getId(), activity.getUserId(), segment.getId(), elapsed);
        }

        log.info("Segment matching complete for activityId={}: {} match(es) across {} candidate segment(s)",
            activity.getId(), matches, segments.size());
    }

    /**
     * Return the index of the first route point at or after {@code fromIdx} whose location is within
     * {@code radiusMeters} of the target coordinate, or -1 if none.
     */
    private int firstPointWithinRadius(List<RoutePoint> points, int fromIdx,
                                       double targetLat, double targetLng, double radiusMeters) {
        for (int i = fromIdx; i < points.size(); i++) {
            RoutePoint p = points.get(i);
            double d = statsCalculator.haversineMeters(p.getLatitude(), p.getLongitude(), targetLat, targetLng);
            if (d <= radiusMeters) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Fetch the leaderboard for a segment (fastest first).
     *
     * <p>Served from the Redis ZSET; on an empty/unavailable cache it falls back to the efforts table,
     * de-duplicates to each athlete's best time, warms the cache, and returns the top {@code limit}.
     *
     * @param segmentId the segment to rank
     * @param limit     maximum number of ranked entries to return (non-positive uses the default)
     * @return the ranked leaderboard
     * @throws NoSuchElementException if the segment does not exist
     */
    public LeaderboardResponse getLeaderboard(String segmentId, int limit) {
        Segment segment = segmentRepository.findById(segmentId)
            .orElseThrow(() -> new NoSuchElementException("Segment not found: " + segmentId));

        int effectiveLimit = limit > 0 ? limit : defaultLimit;

        List<LeaderboardEntry> entries = new ArrayList<>();
        List<LeaderboardCacheService.Entry> cached = leaderboardCacheService.top(segmentId, effectiveLimit);

        if (!cached.isEmpty()) {
            int rank = 1;
            for (LeaderboardCacheService.Entry entry : cached) {
                entries.add(buildEntry(rank++, entry.userId(), entry.elapsedTimeSeconds(), null));
            }
            log.debug("Leaderboard segmentId={} served from cache with {} entries", segmentId, entries.size());
        } else {
            // Cold-cache fallback: pull a wider slice from the DB and dedup to each athlete's best.
            List<SegmentEffort> efforts = segmentEffortRepository.findBySegmentIdOrderByElapsedTimeSecondsAsc(
                segmentId, PageRequest.of(0, effectiveLimit * 3));

            Map<String, SegmentEffort> bestByUser = new LinkedHashMap<>();
            for (SegmentEffort effort : efforts) {
                // Efforts are sorted ascending by time, so the first seen per user is the best.
                bestByUser.putIfAbsent(effort.getUserId(), effort);
            }

            int rank = 1;
            for (SegmentEffort effort : bestByUser.values()) {
                if (rank > effectiveLimit) {
                    break;
                }
                // Warm the cache so subsequent reads are served from Redis.
                leaderboardCacheService.addEffort(segmentId, effort.getUserId(), effort.getElapsedTimeSeconds());
                entries.add(buildEntry(rank++, effort.getUserId(), effort.getElapsedTimeSeconds(),
                    effort.getActivityId()));
            }
            log.debug("Leaderboard segmentId={} served from DB fallback with {} entries", segmentId, entries.size());
        }

        LeaderboardResponse response = new LeaderboardResponse();
        response.setSegmentId(segment.getId());
        response.setSegmentName(segment.getName());
        response.setEntries(entries);
        return response;
    }

    /**
     * List all segments (for the segments discovery endpoint).
     *
     * @return every segment
     */
    public List<Segment> listSegments() {
        return segmentRepository.findAll();
    }

    /**
     * Build a single ranked leaderboard entry, resolving the athlete's display name.
     */
    private LeaderboardEntry buildEntry(int rank, String userId, long elapsedTimeSeconds, String activityId) {
        LeaderboardEntry entry = new LeaderboardEntry();
        entry.setRank(rank);
        entry.setUserId(userId);
        entry.setUserName(userService.resolveName(userId));
        entry.setElapsedTimeSeconds(elapsedTimeSeconds);
        entry.setActivityId(activityId);
        return entry;
    }
}
