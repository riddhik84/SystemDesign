package com.systemdesign.strava.dto;

import com.systemdesign.strava.model.ActivityStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight snapshot returned after ingesting a GPS batch. {@code lastSequence}
 * lets the client confirm which points the server has already applied.
 */
@Data
@NoArgsConstructor
public class ActivityStatsResponse {
    private String activityId;
    private ActivityStatus status;
    private long elapsedTimeSeconds;
    private long movingTimeSeconds;
    private double distanceMeters;
    private double elevationGainMeters;
    private double averageSpeedMps;
    private int pointCount;
    private long lastSequence;
}
