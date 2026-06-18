package com.systemdesign.ticketmaster.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sections", indexes = {
    @Index(name = "idx_venue", columnList = "venue_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Section {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Integer rowCount;

    @Column(nullable = false)
    private Integer seatsPerRow;
}
