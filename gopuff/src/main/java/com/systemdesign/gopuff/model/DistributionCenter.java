package com.systemdesign.gopuff.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A distribution center (micro-fulfillment hub). Geo-located via latitude/longitude so we
 * can compute Haversine distance to a user. {@code regionCode} is a zipcode prefix used as
 * a coarse partitioning / pre-filtering key in a sharded deployment.
 */
@Entity
@Table(name = "distribution_centers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DistributionCenter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dc_id", unique = true, nullable = false, length = 36)
    private String dcId;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    /** Zipcode prefix, e.g. "100" covers 10001-10099. */
    @Column(name = "region_code", length = 10)
    private String regionCode;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
