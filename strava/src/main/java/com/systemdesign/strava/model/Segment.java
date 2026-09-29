package com.systemdesign.strava.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A named stretch of road or trail with fixed start/end coordinates that athletes compete over.
 * When an activity completes, its route is matched against segments of the same {@link ActivityType}:
 * a route point within {@code matchRadiusMeters} of the start followed by a later point within the
 * same radius of the end yields a {@link SegmentEffort} timed between those two samples.
 */
@Entity
@Table(name = "segments")
@Data
@NoArgsConstructor
public class Segment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type")
    private ActivityType activityType;

    private double startLat;

    private double startLng;

    private double endLat;

    private double endLng;

    private double distanceMeters;

    /** Radius in meters within which a route point counts as passing the segment start/end. */
    private double matchRadiusMeters = 30;
}
