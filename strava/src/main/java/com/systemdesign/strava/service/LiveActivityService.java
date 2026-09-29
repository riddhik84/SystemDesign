package com.systemdesign.strava.service;

import com.systemdesign.strava.cache.LiveLocationCacheService;
import com.systemdesign.strava.dto.LiveLocationResponse;
import com.systemdesign.strava.model.Activity;
import com.systemdesign.strava.repository.ActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Live-tracking read service ("follow my run/ride in real time").
 *
 * <p>While an athlete records, the phone syncs GPS batches to the server and each batch refreshes a
 * short-TTL Redis entry with the athlete's latest position and running aggregates. Spectators read
 * that hot entry here. If the entry is missing (TTL expiry between batches, or a Redis outage) we
 * degrade to the activity's last persisted running-state so the endpoint never fails on a cache miss.
 */
@Service
public class LiveActivityService {

    private static final Logger log = LoggerFactory.getLogger(LiveActivityService.class);

    private final ActivityRepository activityRepository;
    private final LiveLocationCacheService liveLocationCacheService;

    public LiveActivityService(ActivityRepository activityRepository,
                               LiveLocationCacheService liveLocationCacheService) {
        this.activityRepository = activityRepository;
        this.liveLocationCacheService = liveLocationCacheService;
    }

    /**
     * Resolve the live location for an activity: hot Redis entry first, else the last persisted
     * running-state snapshot from the activity row.
     *
     * @param activityId the activity being tracked
     * @return the latest known live location
     * @throws NoSuchElementException if the activity does not exist
     */
    public LiveLocationResponse getLiveLocation(String activityId) {
        return liveLocationCacheService.getLocation(activityId)
            .orElseGet(() -> {
                Activity activity = activityRepository.findById(activityId)
                    .orElseThrow(() -> new NoSuchElementException("Activity not found: " + activityId));
                log.debug("Live-location cache miss for activityId={}; serving persisted running-state",
                    activityId);
                return fallbackLocation(activity);
            });
    }

    /**
     * Build a live-location snapshot from the activity's last persisted running-state fields.
     */
    private LiveLocationResponse fallbackLocation(Activity activity) {
        LiveLocationResponse resp = new LiveLocationResponse();
        resp.setActivityId(activity.getId());
        resp.setUserId(activity.getUserId());
        resp.setLatitude(activity.getLastLat() != null ? activity.getLastLat() : 0.0);
        resp.setLongitude(activity.getLastLng() != null ? activity.getLastLng() : 0.0);
        resp.setDistanceMeters(activity.getDistanceMeters());
        resp.setElapsedTimeSeconds(activity.getElapsedTimeSeconds());
        resp.setUpdatedAt(activity.getLastPointTime() != null
            ? activity.getLastPointTime() : activity.getUpdatedAt());
        return resp;
    }
}
