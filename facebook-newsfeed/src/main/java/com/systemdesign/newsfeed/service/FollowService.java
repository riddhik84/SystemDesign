package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.model.Follow;
import com.systemdesign.newsfeed.repository.FollowRepository;
import com.systemdesign.newsfeed.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Follow / unfollow relationship service.
 *
 * Responsibilities:
 * - Create and remove follow relationships (with validation and idempotency)
 * - Maintain follower/following counters
 * - Flip the celebrity flag atomically as follower count crosses the configured threshold
 *   (the celebrity flag drives the hybrid fanout strategy: celebrities are fanned out on read)
 */
@Service
public class FollowService {

    private static final Logger log = LoggerFactory.getLogger(FollowService.class);

    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    @Value("${app.feed.celebrity-follower-threshold}")
    private long celebrityFollowerThreshold;

    public FollowService(FollowRepository followRepository,
                         UserRepository userRepository,
                         UserService userService) {
        this.followRepository = followRepository;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    /**
     * Create a follow relationship (follower -> followee).
     *
     * @param followerId the user initiating the follow
     * @param followeeId the user being followed
     * @throws IllegalArgumentException if a user tries to follow themselves
     * @throws java.util.NoSuchElementException if either user does not exist
     * @throws IllegalStateException if the follow relationship already exists
     */
    @Transactional
    public void follow(String followerId, String followeeId) {
        // Cannot follow yourself
        if (followerId.equals(followeeId)) {
            throw new IllegalArgumentException("cannot follow yourself");
        }

        // Validate both users exist (throws NoSuchElementException -> 404 if missing)
        userService.getUser(followerId);
        userService.getUser(followeeId);

        // Reject duplicate follow
        if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
            throw new IllegalStateException("already following user: " + followeeId);
        }

        // Persist the relationship
        Follow follow = new Follow();
        follow.setFollowerId(followerId);
        follow.setFolloweeId(followeeId);
        followRepository.save(follow);

        // Maintain counters
        userRepository.incrementFollowerCount(followeeId, 1);
        userRepository.incrementFollowingCount(followerId, 1);

        // Atomically promote to celebrity if follower count crosses the threshold
        int updated = userRepository.setCelebrityIfCountAboveThreshold(
            followeeId, celebrityFollowerThreshold, true);
        if (updated > 0) {
            Long followerCount = userRepository.getFollowerCount(followeeId);
            log.info("User id={} became celebrity with {} followers", followeeId, followerCount);
        }

        log.info("User id={} followed user id={}", followerId, followeeId);
    }

    /**
     * Remove a follow relationship (idempotent no-op if it does not exist).
     *
     * @param followerId the user initiating the unfollow
     * @param followeeId the user being unfollowed
     */
    @Transactional
    public void unfollow(String followerId, String followeeId) {
        if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
            followRepository.deleteByFollowerIdAndFolloweeId(followerId, followeeId);

            // Maintain counters
            userRepository.incrementFollowerCount(followeeId, -1);
            userRepository.incrementFollowingCount(followerId, -1);

            // Atomically demote from celebrity if follower count drops below the threshold
            int updated = userRepository.setCelebrityIfCountBelowThreshold(
                followeeId, celebrityFollowerThreshold, false);
            if (updated > 0) {
                Long followerCount = userRepository.getFollowerCount(followeeId);
                log.info("User id={} lost celebrity status with {} followers", followeeId, followerCount);
            }

            log.info("User id={} unfollowed user id={}", followerId, followeeId);
        }
    }

    /**
     * Get the ids of all users that {@code userId} follows.
     */
    public List<String> getFolloweeIds(String userId) {
        return followRepository.findFolloweeIds(userId);
    }

    /**
     * Get the ids of all users that follow {@code userId}.
     */
    public List<String> getFollowerIds(String userId) {
        return followRepository.findFollowerIds(userId);
    }

    /**
     * Whether the given user is currently flagged as a celebrity.
     */
    public boolean isCelebrity(String userId) {
        return userService.getUser(userId).isCelebrity();
    }
}
