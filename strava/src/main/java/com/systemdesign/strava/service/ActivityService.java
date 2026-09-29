package com.systemdesign.strava.service;

import com.systemdesign.strava.dto.ActivityResponse;
import com.systemdesign.strava.dto.GpsPointDto;
import com.systemdesign.strava.dto.StartActivityRequest;
import com.systemdesign.strava.event.ActivityCompletedEvent;
import com.systemdesign.strava.model.Activity;
import com.systemdesign.strava.model.ActivityStatus;
import com.systemdesign.strava.model.RoutePoint;
import com.systemdesign.strava.repository.ActivityRepository;
import com.systemdesign.strava.repository.RoutePointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Activity lifecycle and read service.
 *
 * An activity models one recording session (a run or a ride). Its lifecycle is a small state
 * machine:
 *   ACTIVE  --pause-->  PAUSED  --resume-->  ACTIVE  --stop-->  COMPLETED
 *   ACTIVE  --stop-->   COMPLETED
 * Illegal transitions (e.g. pausing a completed activity) raise IllegalStateException -> 400.
 *
 * While ACTIVE/PAUSED the phone is the source of truth: it samples GPS and syncs batches to
 * GpsIngestionService, which maintains the running aggregate stats stored on the Activity. On
 * stop we finalize those aggregates and publish an ActivityCompletedEvent so downstream
 * consumers (e.g. segment leaderboards) can process the completed route AFTER commit.
 */
@Service
public class ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    private final ActivityRepository activityRepository;
    private final RoutePointRepository routePointRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    public ActivityService(ActivityRepository activityRepository,
                           RoutePointRepository routePointRepository,
                           UserService userService,
                           ApplicationEventPublisher eventPublisher) {
        this.activityRepository = activityRepository;
        this.routePointRepository = routePointRepository;
        this.userService = userService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Start a new activity for a user. The activity begins in the ACTIVE state with startTime=now
     * and zeroed aggregates; the phone will stream GPS batches into it as recording proceeds.
     *
     * @param req start request (userId + type + optional title)
     * @return the created activity as an ActivityResponse
     * @throws NoSuchElementException if the user does not exist
     */
    @Transactional
    public ActivityResponse startActivity(StartActivityRequest req) {
        // Validate the user exists (throws NoSuchElementException -> 404 if missing)
        userService.getUserEntity(req.getUserId());

        Activity activity = new Activity();
        activity.setUserId(req.getUserId());
        activity.setType(req.getType());
        activity.setStatus(ActivityStatus.ACTIVE);
        activity.setTitle(req.getTitle());
        activity.setStartTime(LocalDateTime.now());

        Activity saved = activityRepository.save(activity);
        log.info("Started activity id={} userId={} type={}", saved.getId(), saved.getUserId(), saved.getType());
        return toResponse(saved);
    }

    /**
     * Pause an ACTIVE activity.
     *
     * @param id activity id
     * @return the updated activity
     * @throws NoSuchElementException if the activity does not exist
     * @throws IllegalStateException  if the activity is not currently ACTIVE
     */
    @Transactional
    public ActivityResponse pauseActivity(String id) {
        Activity activity = getActivityEntity(id);
        if (activity.getStatus() != ActivityStatus.ACTIVE) {
            throw new IllegalStateException("Cannot pause activity in status " + activity.getStatus() + ": " + id);
        }
        activity.setStatus(ActivityStatus.PAUSED);
        Activity saved = activityRepository.save(activity);
        log.info("Paused activity id={}", id);
        return toResponse(saved);
    }

    /**
     * Resume a PAUSED activity.
     *
     * @param id activity id
     * @return the updated activity
     * @throws NoSuchElementException if the activity does not exist
     * @throws IllegalStateException  if the activity is not currently PAUSED
     */
    @Transactional
    public ActivityResponse resumeActivity(String id) {
        Activity activity = getActivityEntity(id);
        if (activity.getStatus() != ActivityStatus.PAUSED) {
            throw new IllegalStateException("Cannot resume activity in status " + activity.getStatus() + ": " + id);
        }
        activity.setStatus(ActivityStatus.ACTIVE);
        Activity saved = activityRepository.save(activity);
        log.info("Resumed activity id={}", id);
        return toResponse(saved);
    }

    /**
     * Stop (complete) an ACTIVE or PAUSED activity: set endTime=now, finalize aggregates and
     * publish an ActivityCompletedEvent. The event is published inside this transactional method;
     * a @TransactionalEventListener(AFTER_COMMIT) consumer therefore only observes committed data.
     *
     * @param id activity id
     * @return the finalized activity
     * @throws NoSuchElementException if the activity does not exist
     * @throws IllegalStateException  if the activity is already COMPLETED
     */
    @Transactional
    public ActivityResponse stopActivity(String id) {
        Activity activity = getActivityEntity(id);
        if (activity.getStatus() == ActivityStatus.COMPLETED) {
            throw new IllegalStateException("Activity already completed: " + id);
        }

        activity.setStatus(ActivityStatus.COMPLETED);
        activity.setEndTime(LocalDateTime.now());

        // Finalize the average speed from the accumulated moving time / distance.
        activity.setAverageSpeedMps(
            activity.getMovingTimeSeconds() > 0
                ? activity.getDistanceMeters() / activity.getMovingTimeSeconds()
                : 0d);

        Activity saved = activityRepository.save(activity);

        // Publish AFTER-COMMIT-consumed event so leaderboard matching runs on the completed route.
        eventPublisher.publishEvent(new ActivityCompletedEvent(this, saved.getId(), saved.getUserId()));

        log.info("Completed activity id={} userId={} distanceMeters={} movingTimeSeconds={}",
            saved.getId(), saved.getUserId(), saved.getDistanceMeters(), saved.getMovingTimeSeconds());
        return toResponse(saved);
    }

    /**
     * Fetch an activity by id as an ActivityResponse.
     *
     * @param id activity id
     * @return the ActivityResponse
     * @throws NoSuchElementException if the activity does not exist
     */
    public ActivityResponse getActivity(String id) {
        return toResponse(getActivityEntity(id));
    }

    /**
     * List a user's activities, most recent first, paginated.
     *
     * @param userId the owner's id
     * @param page   zero-based page number
     * @param size   page size
     * @return list of ActivityResponse
     */
    public List<ActivityResponse> listActivities(String userId, int page, int size) {
        return activityRepository
            .findByUserIdOrderByStartTimeDesc(userId, PageRequest.of(page, size))
            .stream()
            .map(this::toResponse)
            .toList();
    }

    /**
     * Return an activity's full route as GPS points ordered by their client-assigned sequence.
     *
     * @param activityId activity id
     * @return the ordered list of GpsPointDto
     */
    public List<GpsPointDto> getRoute(String activityId) {
        return routePointRepository.findByActivityIdOrderBySequenceAsc(activityId)
            .stream()
            .map(this::toGpsPointDto)
            .toList();
    }

    /**
     * Convert an Activity entity to an ActivityResponse DTO, resolving the owner's display name.
     * Kept public with this exact signature because other services (feeds) build responses through it.
     *
     * @param a the Activity entity
     * @return the ActivityResponse DTO
     */
    public ActivityResponse toResponse(Activity a) {
        ActivityResponse response = new ActivityResponse();
        response.setId(a.getId());
        response.setUserId(a.getUserId());
        response.setUserName(userService.resolveName(a.getUserId()));
        response.setType(a.getType());
        response.setStatus(a.getStatus());
        response.setTitle(a.getTitle());
        response.setStartTime(a.getStartTime());
        response.setEndTime(a.getEndTime());
        response.setElapsedTimeSeconds(a.getElapsedTimeSeconds());
        response.setMovingTimeSeconds(a.getMovingTimeSeconds());
        response.setDistanceMeters(a.getDistanceMeters());
        response.setElevationGainMeters(a.getElevationGainMeters());
        response.setAverageSpeedMps(a.getAverageSpeedMps());
        response.setPointCount(a.getPointCount());
        return response;
    }

    /**
     * Load an activity entity or throw.
     *
     * @param id activity id
     * @return the Activity entity
     * @throws NoSuchElementException if the activity does not exist
     */
    private Activity getActivityEntity(String id) {
        return activityRepository.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Activity not found: " + id));
    }

    /**
     * Map a persisted RoutePoint to its GpsPointDto representation.
     */
    private GpsPointDto toGpsPointDto(RoutePoint p) {
        GpsPointDto dto = new GpsPointDto();
        dto.setSequence(p.getSequence());
        dto.setLatitude(p.getLatitude());
        dto.setLongitude(p.getLongitude());
        dto.setElevationMeters(p.getElevationMeters());
        dto.setRecordedAt(p.getRecordedAt());
        return dto;
    }
}
