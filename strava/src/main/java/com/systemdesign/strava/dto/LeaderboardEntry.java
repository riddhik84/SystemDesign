package com.systemdesign.strava.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One ranked row on a segment leaderboard.
 */
@Data
@NoArgsConstructor
public class LeaderboardEntry {
    private int rank;
    private String userId;
    private String userName;
    private long elapsedTimeSeconds;
    private String activityId;
}
