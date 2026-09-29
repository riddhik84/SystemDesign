package com.systemdesign.strava.repository;

import com.systemdesign.strava.model.SegmentEffort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SegmentEffortRepository extends JpaRepository<SegmentEffort, String> {

    /**
     * Get the fastest efforts on a segment (ascending elapsed time).
     * Backs the leaderboard DB fallback when the Redis ZSET cache is cold or unavailable.
     */
    List<SegmentEffort> findBySegmentIdOrderByElapsedTimeSecondsAsc(String segmentId, Pageable pageable);
}
