package com.systemdesign.strava.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A single GPS sample belonging to an activity, as recorded on the phone.
 *
 * <p>The unique constraint on {@code (activity_id, sequence)} enforces at-most-once storage per
 * client-assigned sequence, which — combined with {@link Activity#getLastSequence()} — makes batch
 * ingestion idempotent under retries. Points are appended and later read back in sequence order to
 * reconstruct the route.
 */
@Entity
@Table(name = "route_points",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_route_activity_seq", columnNames = {"activity_id", "sequence"})
    },
    indexes = {
        @Index(name = "idx_route_activity", columnList = "activity_id")
    }
)
@Data
@NoArgsConstructor
public class RoutePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "activity_id", nullable = false)
    private String activityId;

    /** Client-assigned monotonic sequence within the activity; defines ordering and idempotency. */
    private long sequence;

    private double latitude;

    private double longitude;

    @Column(name = "elevation_meters")
    private double elevationMeters;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;
}
