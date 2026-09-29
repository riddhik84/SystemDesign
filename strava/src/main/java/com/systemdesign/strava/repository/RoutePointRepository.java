package com.systemdesign.strava.repository;

import com.systemdesign.strava.model.RoutePoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoutePointRepository extends JpaRepository<RoutePoint, String> {

    /**
     * Retrieve an activity's full route in recorded order.
     * Used to render the route and to match the route against segments on completion.
     */
    List<RoutePoint> findByActivityIdOrderBySequenceAsc(String activityId);
}
