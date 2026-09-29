package com.systemdesign.strava.controller;

import com.systemdesign.strava.dto.ActivityResponse;
import com.systemdesign.strava.dto.LiveLocationResponse;
import com.systemdesign.strava.service.FeedService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for the social feed: friends' completed activities and their
 * currently-live activities.
 */
@RestController
@RequestMapping("/api")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * Get the feed of friends' completed activities, most recent first.
     */
    @GetMapping("/users/{userId}/feed")
    public List<ActivityResponse> getFeed(@PathVariable String userId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return feedService.getFriendFeed(userId, page, size);
    }

    /**
     * Get the live locations of friends who are currently recording an activity.
     */
    @GetMapping("/users/{userId}/feed/live")
    public List<LiveLocationResponse> getLiveFeed(@PathVariable String userId) {
        return feedService.getLiveFriendActivities(userId);
    }
}
