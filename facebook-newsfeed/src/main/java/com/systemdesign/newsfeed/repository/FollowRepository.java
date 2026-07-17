package com.systemdesign.newsfeed.repository;

import com.systemdesign.newsfeed.model.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow, String> {

    /**
     * Find a specific follow relationship.
     * Used to check if a follow already exists before creating one.
     */
    Optional<Follow> findByFollowerIdAndFolloweeId(String followerId, String followeeId);

    /**
     * Check if a follow relationship exists.
     * Used to prevent duplicate follows.
     */
    boolean existsByFollowerIdAndFolloweeId(String followerId, String followeeId);

    /**
     * Count the number of followers for a given user.
     * Used to determine if a user crosses the celebrity threshold.
     */
    long countByFolloweeId(String followeeId);

    /**
     * Get all users that a given user follows (followees).
     * Used by FeedService to determine whose posts to show.
     */
    @Query("SELECT f.followeeId FROM Follow f WHERE f.followerId = :uid")
    List<String> findFolloweeIds(@Param("uid") String uid);

    /**
     * Get all users that follow a given user (followers).
     * Used by FanoutService to push posts to follower feeds.
     */
    @Query("SELECT f.followerId FROM Follow f WHERE f.followeeId = :uid")
    List<String> findFollowerIds(@Param("uid") String uid);

    /**
     * Delete a specific follow relationship.
     * Called by FollowService.unfollow.
     */
    @Modifying
    @Transactional
    void deleteByFollowerIdAndFolloweeId(String followerId, String followeeId);
}
