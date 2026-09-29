package com.systemdesign.strava.controller;

import com.systemdesign.strava.dto.LeaderboardResponse;
import com.systemdesign.strava.model.Segment;
import com.systemdesign.strava.service.LeaderboardService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for segments and their leaderboards.
 */
@RestController
@RequestMapping("/api")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    public LeaderboardController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    /**
     * List all defined segments.
     */
    @GetMapping("/segments")
    public List<Segment> listSegments() {
        return leaderboardService.listSegments();
    }

    /**
     * Get the leaderboard for a segment, fastest efforts first.
     */
    @GetMapping("/segments/{segmentId}/leaderboard")
    public LeaderboardResponse getLeaderboard(@PathVariable String segmentId,
                                              @RequestParam(defaultValue = "10") int limit) {
        return leaderboardService.getLeaderboard(segmentId, limit);
    }
}
