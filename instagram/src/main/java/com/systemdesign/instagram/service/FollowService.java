package com.systemdesign.instagram.service;

import com.systemdesign.instagram.model.Follow;
import com.systemdesign.instagram.repository.FollowRepository;
import com.systemdesign.instagram.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    @Transactional
    public void follow(String followerId, String followeeId) {
        // Validate: cannot follow yourself
        if (followerId.equals(followeeId)) {
            throw new IllegalArgumentException("cannot follow yourself");
        }

        // Validate both users exist
        userService.getUser(followerId);
        userService.getUser(followeeId);

        // Check if already following
        if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
            throw new IllegalStateException("already following user: " + followeeId);
        }

        // Create follow relationship
        Follow follow = new Follow();
        follow.setFollowerId(followerId);
        follow.setFolloweeId(followeeId);
        followRepository.save(follow);

        // Update counts
        userRepository.incrementFollowerCount(followeeId, 1);
        userRepository.incrementFollowingCount(followerId, 1);

        // Atomically set celebrity flag if count crosses threshold
        int updated = userRepository.setCelebrityIfCountAboveThreshold(followeeId, celebrityFollowerThreshold, true);
        if (updated > 0) {
            Long followerCount = userRepository.getFollowerCount(followeeId);
            log.info("User id={} became celebrity with {} followers", followeeId, followerCount);
        }

        log.info("User id={} followed user id={}", followerId, followeeId);
    }

    @Transactional
    public void unfollow(String followerId, String followeeId) {
        // Check if follow relationship exists
        if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
            // Delete the follow relationship
            followRepository.deleteByFollowerIdAndFolloweeId(followerId, followeeId);

            // Update counts
            userRepository.incrementFollowerCount(followeeId, -1);
            userRepository.incrementFollowingCount(followerId, -1);

            // Atomically clear celebrity flag if count drops below threshold
            int updated = userRepository.setCelebrityIfCountBelowThreshold(followeeId, celebrityFollowerThreshold, false);
            if (updated > 0) {
                Long followerCount = userRepository.getFollowerCount(followeeId);
                log.info("User id={} lost celebrity status with {} followers", followeeId, followerCount);
            }

            log.info("User id={} unfollowed user id={}", followerId, followeeId);
        }
    }

    public List<String> getFolloweeIds(String userId) {
        return followRepository.findFolloweeIds(userId);
    }

    public List<String> getFollowerIds(String userId) {
        return followRepository.findFollowerIds(userId);
    }

    public boolean isCelebrity(String userId) {
        return userService.getUser(userId).isCelebrity();
    }
}
