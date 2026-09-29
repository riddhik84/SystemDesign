package com.systemdesign.strava.repository;

import com.systemdesign.strava.model.Activity;
import com.systemdesign.strava.model.ActivityStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, String> {

    /**
     * Retrieve a single athlete's activities in reverse chronological order.
     * Backs the athlete profile / activity list.
     */
    List<Activity> findByUserIdOrderByStartTimeDesc(String userId, Pageable pageable);

    /**
     * Retrieve activities from a set of athletes filtered by status, newest first.
     * Used by the feed (status=COMPLETED across friends) and the live view (status=ACTIVE across friends).
     */
    List<Activity> findByUserIdInAndStatusOrderByStartTimeDesc(
        Collection<String> userIds,
        ActivityStatus status,
        Pageable pageable
    );
}
