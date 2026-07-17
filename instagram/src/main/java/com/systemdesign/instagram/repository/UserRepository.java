package com.systemdesign.instagram.repository;

import com.systemdesign.instagram.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUsername(String username);

    /**
     * Atomic increment/decrement of follower count.
     * Called by FollowService when a user gains or loses a follower.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.followerCount = u.followerCount + :delta WHERE u.id = :id")
    void incrementFollowerCount(@Param("id") String id, @Param("delta") long delta);

    /**
     * Atomic increment/decrement of following count.
     * Called by FollowService when a user follows or unfollows another.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.followingCount = u.followingCount + :delta WHERE u.id = :id")
    void incrementFollowingCount(@Param("id") String id, @Param("delta") long delta);

    /**
     * Atomic increment/decrement of post count.
     * Called by PostService when a user creates a post.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.postCount = u.postCount + :delta WHERE u.id = :id")
    void incrementPostCount(@Param("id") String id, @Param("delta") long delta);

    /**
     * Set or clear celebrity flag.
     * Called by FollowService when follower count crosses celebrity threshold.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.celebrity = :flag WHERE u.id = :id")
    void setCelebrity(@Param("id") String id, @Param("flag") boolean flag);

    /**
     * Get current follower count for a user.
     * Used by FollowService to check celebrity threshold after increment.
     */
    @Query("SELECT u.followerCount FROM User u WHERE u.id = :id")
    Long getFollowerCount(@Param("id") String id);

    /**
     * Atomically set celebrity flag if count crosses threshold.
     * Returns number of rows updated (1 if flag was set, 0 if already set or below threshold).
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.celebrity = :flag WHERE u.id = :id AND u.followerCount >= :threshold AND u.celebrity != :flag")
    int setCelebrityIfCountAboveThreshold(@Param("id") String id, @Param("threshold") long threshold, @Param("flag") boolean flag);

    /**
     * Atomically clear celebrity flag if count drops below threshold.
     * Returns number of rows updated (1 if flag was cleared, 0 if already cleared or above threshold).
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.celebrity = :flag WHERE u.id = :id AND u.followerCount < :threshold AND u.celebrity != :flag")
    int setCelebrityIfCountBelowThreshold(@Param("id") String id, @Param("threshold") long threshold, @Param("flag") boolean flag);
}
