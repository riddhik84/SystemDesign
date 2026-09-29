package com.systemdesign.strava.config;

import com.systemdesign.strava.cache.LeaderboardCacheService;
import com.systemdesign.strava.model.Activity;
import com.systemdesign.strava.model.ActivityStatus;
import com.systemdesign.strava.model.ActivityType;
import com.systemdesign.strava.model.Friendship;
import com.systemdesign.strava.model.RoutePoint;
import com.systemdesign.strava.model.Segment;
import com.systemdesign.strava.model.SegmentEffort;
import com.systemdesign.strava.model.User;
import com.systemdesign.strava.repository.ActivityRepository;
import com.systemdesign.strava.repository.FriendshipRepository;
import com.systemdesign.strava.repository.RoutePointRepository;
import com.systemdesign.strava.repository.SegmentEffortRepository;
import com.systemdesign.strava.repository.SegmentRepository;
import com.systemdesign.strava.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Loads sample data on startup for local development and demos.
 *
 * <p>Creates users, bidirectional friendships, two segments (one RUN, one RIDE), and a couple of
 * COMPLETED activities (Bob's run, Carol's ride) with a handful of {@link RoutePoint}s each. The
 * activities' aggregate stats are set directly to consistent values, matching {@link SegmentEffort}
 * rows are inserted, and the leaderboard cache is warmed (best-effort). This makes the friend feed,
 * route, live-location, and leaderboard endpoints non-empty out of the box.
 *
 * <p>Disabled under the {@code test} profile so tests start from a clean slate. Guarded by a
 * user-count check so it never double-seeds on restart, and the whole routine is wrapped in a
 * try/catch so a Redis-down environment still boots.
 */
@Component
@Profile("!test")
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final FriendshipRepository friendshipRepository;
    private final SegmentRepository segmentRepository;
    private final ActivityRepository activityRepository;
    private final RoutePointRepository routePointRepository;
    private final SegmentEffortRepository segmentEffortRepository;
    private final LeaderboardCacheService leaderboardCacheService;

    public DataInitializer(UserRepository userRepository,
                           FriendshipRepository friendshipRepository,
                           SegmentRepository segmentRepository,
                           ActivityRepository activityRepository,
                           RoutePointRepository routePointRepository,
                           SegmentEffortRepository segmentEffortRepository,
                           LeaderboardCacheService leaderboardCacheService) {
        this.userRepository = userRepository;
        this.friendshipRepository = friendshipRepository;
        this.segmentRepository = segmentRepository;
        this.activityRepository = activityRepository;
        this.routePointRepository = routePointRepository;
        this.segmentEffortRepository = segmentEffortRepository;
        this.leaderboardCacheService = leaderboardCacheService;
    }

    @Override
    public void run(String... args) {
        try {
            // Idempotency guard: only seed into an empty database.
            if (userRepository.count() > 0) {
                log.info("Sample data already present ({} users); skipping seed", userRepository.count());
                return;
            }

            // 1. Users.
            User alice = createUser("Alice Chen", "alice@example.com");
            User bob = createUser("Bob Martinez", "bob@example.com");
            User carol = createUser("Carol Kim", "carol@example.com");
            User dan = createUser("Dan Lee", "dan@example.com");

            // 2. Friendships (store both directions): Alice<->Bob, Alice<->Carol.
            createFriendship(alice.getId(), bob.getId());
            createFriendship(bob.getId(), alice.getId());
            createFriendship(alice.getId(), carol.getId());
            createFriendship(carol.getId(), alice.getId());

            // 3. Segments near Golden Gate Park, San Francisco.
            Segment runSegment = createSegment("JFK Drive Sprint", ActivityType.RUN,
                37.77190, -122.45400, 37.77210, -122.46550, 1015.0);
            Segment rideSegment = createSegment("Marina Boulevard Loop", ActivityType.RIDE,
                37.80600, -122.44000, 37.80650, -122.42500, 1310.0);

            // 4. Bob's completed RUN over the run segment.
            LocalDateTime bobStart = LocalDateTime.now().minusDays(1).withHour(7).withMinute(0).withSecond(0).withNano(0);
            long bobElapsed = 300L; // 5 minutes
            Activity bobRun = createCompletedActivity(bob.getId(), ActivityType.RUN, "Morning shakeout run",
                bobStart, bobElapsed, bobElapsed, 1015.0, 12.0);
            seedRunRoute(bobRun.getId(), bobStart);
            recordEffort(runSegment.getId(), bobRun.getId(), bob.getId(), bobElapsed,
                bobStart.plusSeconds(bobElapsed));

            // 5. Carol's completed RIDE over the ride segment.
            LocalDateTime carolStart = LocalDateTime.now().minusHours(6).withMinute(30).withSecond(0).withNano(0);
            long carolElapsed = 240L; // 4 minutes
            Activity carolRide = createCompletedActivity(carol.getId(), ActivityType.RIDE, "Evening spin",
                carolStart, carolElapsed, carolElapsed, 1310.0, 5.0);
            seedRideRoute(carolRide.getId(), carolStart);
            recordEffort(rideSegment.getId(), carolRide.getId(), carol.getId(), carolElapsed,
                carolStart.plusSeconds(carolElapsed));

            log.info("Sample data initialized: 4 users, 2 friendships (bidirectional), 2 segments, "
                    + "2 completed activities with route points, 2 segment efforts");

        } catch (Exception e) {
            log.error("Error initializing sample data", e);
        }
    }

    private User createUser(String name, String email) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        return userRepository.save(user);
    }

    private void createFriendship(String userId, String friendId) {
        Friendship friendship = new Friendship();
        friendship.setUserId(userId);
        friendship.setFriendId(friendId);
        friendshipRepository.save(friendship);
    }

    private Segment createSegment(String name, ActivityType activityType,
                                  double startLat, double startLng,
                                  double endLat, double endLng, double distanceMeters) {
        Segment segment = new Segment();
        segment.setName(name);
        segment.setActivityType(activityType);
        segment.setStartLat(startLat);
        segment.setStartLng(startLng);
        segment.setEndLat(endLat);
        segment.setEndLng(endLng);
        segment.setDistanceMeters(distanceMeters);
        segment.setMatchRadiusMeters(30.0);
        return segmentRepository.save(segment);
    }

    /**
     * Creates a COMPLETED activity with pre-computed, self-consistent aggregate stats (as if the
     * client had synced its full batch history). {@code averageSpeedMps} is derived from distance
     * and moving time so it matches what the ingestion path would have produced.
     */
    private Activity createCompletedActivity(String userId, ActivityType type, String title,
                                             LocalDateTime startTime, long elapsedTimeSeconds,
                                             long movingTimeSeconds, double distanceMeters,
                                             double elevationGainMeters) {
        Activity activity = new Activity();
        activity.setUserId(userId);
        activity.setType(type);
        activity.setStatus(ActivityStatus.COMPLETED);
        activity.setTitle(title);
        activity.setStartTime(startTime);
        activity.setEndTime(startTime.plusSeconds(elapsedTimeSeconds));
        activity.setElapsedTimeSeconds(elapsedTimeSeconds);
        activity.setMovingTimeSeconds(movingTimeSeconds);
        activity.setDistanceMeters(distanceMeters);
        activity.setElevationGainMeters(elevationGainMeters);
        activity.setAverageSpeedMps(movingTimeSeconds > 0 ? distanceMeters / movingTimeSeconds : 0.0);
        activity.setPointCount(5);
        activity.setLastSequence(5L);
        return activityRepository.save(activity);
    }

    /** Five GPS samples tracing the RUN segment (start -> end), one per ~75 seconds. */
    private void seedRunRoute(String activityId, LocalDateTime start) {
        addPoint(activityId, 1, 37.77190, -122.45400, 12.0, start);
        addPoint(activityId, 2, 37.77195, -122.45690, 14.0, start.plusSeconds(75));
        addPoint(activityId, 3, 37.77200, -122.45980, 17.0, start.plusSeconds(150));
        addPoint(activityId, 4, 37.77205, -122.46270, 20.0, start.plusSeconds(225));
        addPoint(activityId, 5, 37.77210, -122.46550, 24.0, start.plusSeconds(300));
    }

    /** Five GPS samples tracing the RIDE segment (start -> end), one per ~60 seconds. */
    private void seedRideRoute(String activityId, LocalDateTime start) {
        addPoint(activityId, 1, 37.80600, -122.44000, 3.0, start);
        addPoint(activityId, 2, 37.80612, -122.43625, 4.0, start.plusSeconds(60));
        addPoint(activityId, 3, 37.80625, -122.43250, 5.0, start.plusSeconds(120));
        addPoint(activityId, 4, 37.80637, -122.42875, 6.0, start.plusSeconds(180));
        addPoint(activityId, 5, 37.80650, -122.42500, 8.0, start.plusSeconds(240));
    }

    private void addPoint(String activityId, long sequence, double latitude, double longitude,
                          double elevationMeters, LocalDateTime recordedAt) {
        RoutePoint point = new RoutePoint();
        point.setActivityId(activityId);
        point.setSequence(sequence);
        point.setLatitude(latitude);
        point.setLongitude(longitude);
        point.setElevationMeters(elevationMeters);
        point.setRecordedAt(recordedAt);
        routePointRepository.save(point);
    }

    /**
     * Persists a segment effort and warms the leaderboard cache. The cache write is best-effort:
     * a Redis outage must not break startup, so failures are swallowed by the cache service itself.
     */
    private void recordEffort(String segmentId, String activityId, String userId,
                              long elapsedTimeSeconds, LocalDateTime achievedAt) {
        SegmentEffort effort = new SegmentEffort();
        effort.setSegmentId(segmentId);
        effort.setActivityId(activityId);
        effort.setUserId(userId);
        effort.setElapsedTimeSeconds(elapsedTimeSeconds);
        effort.setAchievedAt(achievedAt);
        segmentEffortRepository.save(effort);

        leaderboardCacheService.addEffort(segmentId, userId, elapsedTimeSeconds);
    }
}
