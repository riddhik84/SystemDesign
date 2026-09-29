package com.systemdesign.strava.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One athlete's timed traversal of a segment during a specific activity. Efforts are the durable
 * source of truth behind segment leaderboards; the Redis ZSET leaderboard is a cache warmed from
 * these rows. The composite index on {@code (segment_id, elapsed_time_seconds)} makes "fastest
 * efforts on this segment" an indexed range scan for the DB fallback path.
 */
@Entity
@Table(name = "segment_efforts", indexes = {
    @Index(name = "idx_effort_segment_time", columnList = "segment_id, elapsed_time_seconds")
})
@Data
@NoArgsConstructor
public class SegmentEffort {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "segment_id")
    private String segmentId;

    @Column(name = "activity_id")
    private String activityId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "elapsed_time_seconds")
    private long elapsedTimeSeconds;

    @Column(name = "achieved_at")
    private LocalDateTime achievedAt;
}
