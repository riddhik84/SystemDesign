package com.systemdesign.newsfeed.controller;

import com.systemdesign.newsfeed.dto.FeedResponse;
import com.systemdesign.newsfeed.service.FeedService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/feed")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * Get the personalized feed for a user.
     * Uses hybrid fan-out: fan-out-on-write for normal users,
     * fan-out-on-read for celebrity users with many followers.
     * A read-time ranking layer orders posts by recency x engagement x affinity.
     */
    @GetMapping
    public FeedResponse getFeed(
        @RequestParam String userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        // Validate pagination parameters
        if (page < 0 || pageSize <= 0) {
            throw new IllegalArgumentException("page must be >= 0 and pageSize must be > 0");
        }
        if (pageSize > 100) {
            throw new IllegalArgumentException("pageSize must not exceed 100");
        }
        return feedService.getFeed(userId, page, pageSize);
    }
}
