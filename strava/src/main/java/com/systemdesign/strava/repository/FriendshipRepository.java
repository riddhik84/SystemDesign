package com.systemdesign.strava.repository;

import com.systemdesign.strava.model.Friendship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FriendshipRepository extends JpaRepository<Friendship, String> {

    /**
     * Get all friendship edges originating from a user.
     * Used to resolve the friend id set that feeds the activity and live views.
     */
    List<Friendship> findByUserId(String userId);
}
