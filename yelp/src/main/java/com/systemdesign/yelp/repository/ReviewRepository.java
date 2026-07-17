package com.systemdesign.yelp.repository;

import com.systemdesign.yelp.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, String> {

    Page<Review> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    Page<Review> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    Optional<Review> findByBusinessIdAndUserId(String businessId, String userId);

    boolean existsByBusinessIdAndUserId(String businessId, String userId);

    long countByBusinessId(String businessId);

    // Aggregation query for the star distribution histogram
    @Query("""
        SELECT r.stars, COUNT(r) FROM Review r
        WHERE r.businessId = :businessId
        GROUP BY r.stars
        ORDER BY r.stars
        """)
    List<Object[]> countStarsByBusinessId(@Param("businessId") String businessId);

    // Average for rating recalculation after a review write
    @Query("SELECT AVG(r.stars) FROM Review r WHERE r.businessId = :businessId")
    Double averageStarsByBusinessId(@Param("businessId") String businessId);
}
