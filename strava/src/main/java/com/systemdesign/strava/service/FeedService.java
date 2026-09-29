package com.systemdesign.strava.service;

import com.systemdesign.strava.cache.LiveLocationCacheService;
import com.systemdesign.strava.dto.ActivityResponse;
import com.systemdesign.strava.dto.LiveLocationResponse;
import com.systemdesign.strava.model.Activity;
import com.systemdesign.strava.model.ActivityStatus;
import com.systemdesign.strava.repository.ActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Social feed service: assembles the activity feed and the live-activity view for a user's friends.
 *
 * <p>Two read paths are served here:
 * <ol>
 *   <li><b>Completed-activity feed</b> — the friends' finished RUNs/RIDEs, newest first. This is a
 *       simple read-time fanout: resolve the friend set, then query the activities table
 *       (indexed on {@code (user_id, start_time)}). Because friend counts are small, read-time
 *       fanout is cheap and avoids the write amplification of pre-materialised feeds.</li>
 *   <li><b>Live-activity feed</b> — friends who are currently recording (status {@code ACTIVE}).
 *       Their moving position is served from the Redis live-location cache (updated on every GPS
 *       batch sync); if the cache has expired or is unavailable we degrade gracefully to the
 *       activity's last persisted running-state snapshot.</li>
 * </ol>
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    /** Upper bound on concurrently-live friend activities scanned for the live view. */
    private static final int LIVE_SCAN_LIMIT = 100;

    private final FriendshipService friendshipService;
    private final ActivityRepository activityRepository;
    private final ActivityService activityService;
    private final LiveLocationCacheService liveLocationCacheService;

    public FeedService(FriendshipService friendshipService,
                       ActivityRepository activityRepository,
                       ActivityService activityService,
                       LiveLocationCacheService liveLocationCacheService) {
        this.friendshipService = friendshipService;
        this.activityRepository = activityRepository;
        this.activityService = activityService;
        this.liveLocationCacheService = liveLocationCacheService;
    }

    /**
     * Build the completed-activity feed for a user (their friends' finished activities, newest first).
     *
     * @param userId the viewer requesting the feed
     * @param page   zero-based page number
     * @param size   number of activities per page
     * @return ranked-by-recency list of friend activities (empty if the user has no friends)
     */
    public List<ActivityResponse> getFriendFeed(String userId, int page, int size) {
        List<String> friendIds = friendshipService.getFriendIds(userId);
        if (friendIds.isEmpty()) {
            log.debug("User id={} has no friends; returning empty feed", userId);
            return List.of();
        }

        List<Activity> activities = activityRepository.findByUserIdInAndStatusOrderByStartTimeDesc(
            friendIds, ActivityStatus.COMPLETED, PageRequest.of(page, size));

        List<ActivityResponse> feed = activities.stream()
            .map(activityService::toResponse)
            .collect(Collectors.toList());

        log.info("Feed served userId={} page={} size={} activities={}", userId, page, size, feed.size());
        return feed;
    }

    /**
     * Build the live-activity view for a user: the friends who are currently recording.
     *
     * <p>Each live position is read from the Redis live-location cache and, on a miss (TTL expiry or
     * Redis outage), falls back to the activity's last persisted running-state so the view never
     * breaks on a cache failure.
     *
     * @param userId the viewer requesting the live view
     * @return live locations of friends currently active (empty if the user has no friends)
     */
    public List<LiveLocationResponse> getLiveFriendActivities(String userId) {
        List<String> friendIds = friendshipService.getFriendIds(userId);
        if (friendIds.isEmpty()) {
            log.debug("User id={} has no friends; returning empty live view", userId);
            return List.of();
        }

        List<Activity> active = activityRepository.findByUserIdInAndStatusOrderByStartTimeDesc(
            friendIds, ActivityStatus.ACTIVE, PageRequest.of(0, LIVE_SCAN_LIMIT));

        List<LiveLocationResponse> live = new ArrayList<>(active.size());
        for (Activity activity : active) {
            LiveLocationResponse location = liveLocationCacheService.getLocation(activity.getId())
                .orElseGet(() -> fallbackLocation(activity));
            live.add(location);
        }

        log.info("Live feed served userId={} liveFriends={}", userId, live.size());
        return live;
    }

    /**
     * Build a live-location snapshot from an activity's last persisted running-state.
     * Used when the Redis cache has no fresh entry for the activity.
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
