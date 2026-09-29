package com.systemdesign.strava.model;

/**
 * Lifecycle state of a recorded activity. The phone (source of truth while
 * recording) drives these transitions and syncs them to the server:
 * {@code ACTIVE -> PAUSED -> ACTIVE -> COMPLETED}. Only ACTIVE activities
 * accept GPS batches and surface in the live-friends feed; COMPLETED
 * activities are immutable and appear in the completed feed and leaderboards.
 */
public enum ActivityStatus {
    ACTIVE,
    PAUSED,
    COMPLETED
}
