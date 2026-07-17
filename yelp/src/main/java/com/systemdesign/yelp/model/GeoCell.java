package com.systemdesign.yelp.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a geohash cell in the QuadTree/geohash index.
 *
 * In production this is replaced by Elasticsearch geo_point index or PostGIS,
 * but this table lets us demonstrate the geohash partitioning concept:
 * - Encode lat/lon to a geohash prefix (e.g. "9q8yy")
 * - Index businesses by their geohash cell
 * - Proximity search = find adjacent cells and businesses within them
 *
 * Each row maps a business to the geohash cells at multiple precisions
 * (precision 4 ≈ 39km², precision 6 ≈ 0.6km²) so we can expand the radius
 * by querying a coarser prefix.
 */
@Entity
@Table(name = "geo_index", indexes = {
    @Index(name = "idx_geo_cell", columnList = "geohash_cell"),
    @Index(name = "idx_geo_business", columnList = "business_id")
})
@Data
@NoArgsConstructor
public class GeoCell {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "business_id", nullable = false)
    private String businessId;

    // Geohash of the business location at precision 6 (~0.6km²)
    @Column(name = "geohash_cell", nullable = false, length = 12)
    private String geohashCell;

    // Precision level (4, 5, or 6) so we can query at different radii
    @Column(nullable = false)
    private int precision;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;
}
