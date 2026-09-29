package com.systemdesign.strava.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A recorded workout (run or ride).
 *
 * <p>Core design idea: the phone is the source of truth while recording. It samples GPS,
 * buffers points offline, and syncs them to the server in idempotent batches ordered by a
 * client-assigned monotonic sequence. The server appends those points and maintains the
 * RUNNING aggregate stats on this row from each batch, so the write path is lightweight and
 * shardable by user/activity — enabling scale to millions of concurrent activities.
 *
 * <p>{@code lastSequence} is the idempotency guard: batches whose points have already been
 * applied (sequence &lt;= lastSequence) are skipped, making retries safe. The {@code last*}
 * fields hold the running-state cursor (previous GPS sample) needed to compute the next
 * incremental delta without re-reading the whole route.
 */
@Entity
@Table(name = "activities", indexes = {
    @Index(name = "idx_activity_user", columnList = "user_id"),
    @Index(name = "idx_activity_user_start", columnList = "user_id, start_time")
})
@Data
@NoArgsConstructor
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    private ActivityType type;

    @Enumerated(EnumType.STRING)
    private ActivityStatus status;

    private String title;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** Total wall-clock seconds accumulated across recorded GPS samples. */
    private long elapsedTimeSeconds = 0;

    /** Seconds during which the athlete was actually moving (above the moving-speed threshold). */
    private long movingTimeSeconds = 0;

    /** Cumulative distance in meters, summed from geodesic deltas between consecutive samples. */
    private double distanceMeters = 0;

    /** Cumulative positive elevation change in meters. */
    private double elevationGainMeters = 0;

    /** distanceMeters / movingTimeSeconds, recomputed on each batch. */
    private double averageSpeedMps = 0;

    /** Number of GPS points applied to this activity. */
    private int pointCount = 0;

    /** Highest applied GPS batch sequence; guards against double-applying resynced batches. */
    private long lastSequence = 0;

    // --- Running-state cursor (previous sample) used to compute incremental deltas. Null until first point. ---

    private Double lastLat;

    private Double lastLng;

    private Double lastElevationMeters;

    private LocalDateTime lastPointTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
