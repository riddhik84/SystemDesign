package com.systemdesign.strava.dto;

import com.systemdesign.strava.model.ActivityStatus;
import com.systemdesign.strava.model.ActivityType;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Full view of an activity including its running aggregate stats.
 */
@Data
@NoArgsConstructor
public class ActivityResponse {
    private String id;
    private String userId;
    private String userName;
    private ActivityType type;
    private ActivityStatus status;
    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long elapsedTimeSeconds;
    private long movingTimeSeconds;
    private double distanceMeters;
    private double elevationGainMeters;
    private double averageSpeedMps;
    private int pointCount;
}
