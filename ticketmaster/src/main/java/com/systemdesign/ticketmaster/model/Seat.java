package com.systemdesign.ticketmaster.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "seats", indexes = {
    @Index(name = "idx_event_status", columnList = "event_id,status"),
    @Index(name = "idx_event_section", columnList = "event_id,section_id"),
    @Index(name = "idx_hold_expiry", columnList = "status,hold_expires_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id", nullable = false)
    private Section section;

    @Column(nullable = false, length = 20)
    private String seatNumber;

    @Column(nullable = false, length = 10)
    private String rowNumber;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(length = 100)
    private String heldByUserId;

    private Instant holdExpiresAt;

    @Version
    private Long version;

    public enum SeatStatus {
        AVAILABLE,
        HELD,
        BOOKED
    }
}
