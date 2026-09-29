package com.systemdesign.strava.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Last known position and progress of a currently active activity, served from
 * the live-location cache for friend "beacon" views.
 */
@Data
@NoArgsConstructor
public class LiveLocationResponse {
    private String activityId;
    private String userId;
    private double latitude;
    private double longitude;
    private double distanceMeters;
    private long elapsedTimeSeconds;
    private LocalDateTime updatedAt;
}
