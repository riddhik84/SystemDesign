package com.systemdesign.strava.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Ranked leaderboard for a segment, fastest efforts first.
 */
@Data
@NoArgsConstructor
public class LeaderboardResponse {
    private String segmentId;
    private String segmentName;
    private List<LeaderboardEntry> entries;
}
