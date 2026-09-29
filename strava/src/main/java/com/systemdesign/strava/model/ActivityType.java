package com.systemdesign.strava.model;

/**
 * The kind of physical activity a user records. Segments are matched only
 * against activities of the same type (a running segment does not compete
 * with a cycling effort), so this drives both leaderboard scoping and
 * client-side stat presentation.
 */
public enum ActivityType {
    RUN,
    RIDE
}
