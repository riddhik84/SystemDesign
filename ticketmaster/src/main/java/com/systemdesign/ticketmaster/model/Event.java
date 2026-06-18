package com.systemdesign.ticketmaster.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "events", indexes = {
    @Index(name = "idx_venue_date", columnList = "venue_id,event_date"),
    @Index(name = "idx_date", columnList = "event_date"),
    @Index(name = "idx_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    private String id;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false)
    private Instant eventDate;

    @Column(nullable = false)
    private Instant saleStartDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private EventStatus status = EventStatus.UPCOMING;

    @Column(nullable = false)
    private Integer totalSeats;

    @Column(nullable = false)
    private Integer availableSeats;

    @Column(nullable = false)
    @Builder.Default
    private Integer holdTimeMinutes = 10;

    @Column(nullable = false)
    @Builder.Default
    private Boolean requiresWaitingRoom = false;

    public enum EventStatus {
        UPCOMING,
        ON_SALE,
        SOLD_OUT,
        CANCELLED,
        COMPLETED
    }
}
