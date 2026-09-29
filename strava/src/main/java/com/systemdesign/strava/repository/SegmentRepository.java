package com.systemdesign.strava.repository;

import com.systemdesign.strava.model.ActivityType;
import com.systemdesign.strava.model.Segment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SegmentRepository extends JpaRepository<Segment, String> {

    /**
     * Get all segments of a given activity type.
     * Used on activity completion to match only same-type segments (runs to run segments, etc.).
     */
    List<Segment> findByActivityType(ActivityType type);
}
