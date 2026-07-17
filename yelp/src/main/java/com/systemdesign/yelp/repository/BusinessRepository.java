package com.systemdesign.yelp.repository;

import com.systemdesign.yelp.model.Business;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BusinessRepository extends JpaRepository<Business, String> {

    /**
     * Bounding-box pre-filter before precise Haversine calculation.
     * Converts radiusKm to approximate degree offsets (1 degree lat ≈ 111 km).
     * This returns a superset; BusinessSearchService filters to exact radius.
     */
    @Query("""
        SELECT b FROM Business b
        WHERE b.latitude BETWEEN :minLat AND :maxLat
          AND b.longitude BETWEEN :minLon AND :maxLon
          AND (:category IS NULL OR b.category = :category)
          AND (:minStars IS NULL OR b.starRating >= :minStars)
          AND (:maxPrice IS NULL OR b.priceRange <= :maxPrice)
          AND (:openOnly = false OR b.isOpen = true)
        """)
    List<Business> findByBoundingBox(
        @Param("minLat") double minLat,
        @Param("maxLat") double maxLat,
        @Param("minLon") double minLon,
        @Param("maxLon") double maxLon,
        @Param("category") String category,
        @Param("minStars") Integer minStars,
        @Param("maxPrice") Integer maxPrice,
        @Param("openOnly") boolean openOnly
    );

    /**
     * Full-text search: matches query against name, category, description, and tags.
     * In production this delegates to Elasticsearch; here we use LIKE on H2.
     */
    @Query("""
        SELECT DISTINCT b FROM Business b
        LEFT JOIN b.tags t
        WHERE b.latitude BETWEEN :minLat AND :maxLat
          AND b.longitude BETWEEN :minLon AND :maxLon
          AND (
            LOWER(b.name) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(b.category) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(b.description) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(t) LIKE LOWER(CONCAT('%', :query, '%'))
          )
        """)
    List<Business> searchByTextAndBoundingBox(
        @Param("query") String query,
        @Param("minLat") double minLat,
        @Param("maxLat") double maxLat,
        @Param("minLon") double minLon,
        @Param("maxLon") double maxLon
    );

    List<Business> findByOwnerId(String ownerId);

    /**
     * Atomic update of denormalized rating columns after a review is written.
     * Called by ReviewAggregationService — avoids a full reload+recalc.
     */
    @Modifying
    @Query("""
        UPDATE Business b
        SET b.starRating = :avgRating,
            b.reviewCount = :reviewCount
        WHERE b.id = :businessId
        """)
    void updateRating(
        @Param("businessId") String businessId,
        @Param("avgRating") double avgRating,
        @Param("reviewCount") int reviewCount
    );
}
