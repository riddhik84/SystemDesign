package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.cache.FeedCacheService;
import com.systemdesign.newsfeed.cache.PostCacheService;
import com.systemdesign.newsfeed.dto.CreateUserRequest;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.FollowRepository;
import com.systemdesign.newsfeed.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * FollowService tests: relationship lifecycle, counter maintenance, and the celebrity
 * threshold flip (up and down).
 */
@SpringBootTest
@ActiveProfiles("test")
class FollowServiceTest {

    @Autowired
    private FollowService followService;

    @Autowired
    private UserService userService;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private FeedCacheService feedCacheService;

    @MockBean
    private PostCacheService postCacheService;

    @Value("${app.feed.celebrity-follower-threshold:3}")
    private long celebrityThreshold;

    private User user1;
    private User user2;
    private User user3;

    @BeforeEach
    void setUp() {
        followRepository.deleteAll();
        userRepository.deleteAll();

        when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(postCacheService.get(anyString())).thenReturn(Optional.empty());

        user1 = createUser("alice", "Alice Smith", "alice@example.com");
        user2 = createUser("bob", "Bob Jones", "bob@example.com");
        user3 = createUser("charlie", "Charlie Brown", "charlie@example.com");
    }

    @Test
    void followCreatesRelationshipAndCounts() {
        followService.follow(user1.getId(), user2.getId());

        assertThat(followRepository.existsByFollowerIdAndFolloweeId(user1.getId(), user2.getId())).isTrue();

        User follower = userRepository.findById(user1.getId()).orElseThrow();
        User followee = userRepository.findById(user2.getId()).orElseThrow();
        assertThat(follower.getFollowingCount()).isEqualTo(1);
        assertThat(followee.getFollowerCount()).isEqualTo(1);
    }

    @Test
    void unfollowRemovesRelationshipAndCounts() {
        followService.follow(user1.getId(), user2.getId());
        followService.unfollow(user1.getId(), user2.getId());

        assertThat(followRepository.existsByFollowerIdAndFolloweeId(user1.getId(), user2.getId())).isFalse();

        User follower = userRepository.findById(user1.getId()).orElseThrow();
        User followee = userRepository.findById(user2.getId()).orElseThrow();
        assertThat(follower.getFollowingCount()).isEqualTo(0);
        assertThat(followee.getFollowerCount()).isEqualTo(0);
    }

    @Test
    void unfollowNonExistentRelationshipIsNoOp() {
        followService.unfollow(user1.getId(), user2.getId());

        User follower = userRepository.findById(user1.getId()).orElseThrow();
        User followee = userRepository.findById(user2.getId()).orElseThrow();
        assertThat(follower.getFollowingCount()).isEqualTo(0);
        assertThat(followee.getFollowerCount()).isEqualTo(0);
    }

    @Test
    void duplicateFollowThrowsIllegalState() {
        followService.follow(user1.getId(), user2.getId());

        assertThatThrownBy(() -> followService.follow(user1.getId(), user2.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already following");
    }

    @Test
    void selfFollowThrowsIllegalArgument() {
        assertThatThrownBy(() -> followService.follow(user1.getId(), user1.getId()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot follow yourself");
    }

    @Test
    void followNonExistentUserThrowsNoSuchElement() {
        assertThatThrownBy(() -> followService.follow(user1.getId(), "nonexistent-id"))
            .isInstanceOf(NoSuchElementException.class);

        assertThatThrownBy(() -> followService.follow("nonexistent-id", user2.getId()))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getFolloweeIdsReturnsCorrectUsers() {
        followService.follow(user1.getId(), user2.getId());
        followService.follow(user1.getId(), user3.getId());

        List<String> followeeIds = followService.getFolloweeIds(user1.getId());
        assertThat(followeeIds).containsExactlyInAnyOrder(user2.getId(), user3.getId());
    }

    @Test
    void getFollowerIdsReturnsCorrectUsers() {
        followService.follow(user1.getId(), user2.getId());
        followService.follow(user3.getId(), user2.getId());

        List<String> followerIds = followService.getFollowerIds(user2.getId());
        assertThat(followerIds).containsExactlyInAnyOrder(user1.getId(), user3.getId());
    }

    @Test
    void getFolloweeIdsAndFollowerIdsEmptyWhenNone() {
        assertThat(followService.getFolloweeIds(user1.getId())).isEmpty();
        assertThat(followService.getFollowerIds(user1.getId())).isEmpty();
    }

    @Test
    void crossingCelebrityThresholdSetsCelebrityFlag() {
        User celebrity = createUser("celebrity", "Celebrity User", "celebrity@example.com");

        // Pre-seed to just below the threshold
        userRepository.incrementFollowerCount(celebrity.getId(), celebrityThreshold - 1);

        User fan = createUser("fan", "Fan User", "fan@example.com");
        followService.follow(fan.getId(), celebrity.getId());

        User updated = userRepository.findById(celebrity.getId()).orElseThrow();
        assertThat(updated.getFollowerCount()).isEqualTo(celebrityThreshold);
        assertThat(updated.isCelebrity()).isTrue();
    }

    @Test
    void fallingBelowCelebrityThresholdClearsCelebrityFlag() {
        User celebrity = createUser("celebrity", "Celebrity User", "celebrity@example.com");

        // At threshold with celebrity flag set
        userRepository.incrementFollowerCount(celebrity.getId(), celebrityThreshold - 1);
        userRepository.setCelebrity(celebrity.getId(), true);

        User fan = createUser("fan", "Fan User", "fan@example.com");
        followService.follow(fan.getId(), celebrity.getId());   // now == threshold

        followService.unfollow(fan.getId(), celebrity.getId()); // now < threshold

        User updated = userRepository.findById(celebrity.getId()).orElseThrow();
        assertThat(updated.getFollowerCount()).isEqualTo(celebrityThreshold - 1);
        assertThat(updated.isCelebrity()).isFalse();
    }

    @Test
    void isCelebrityReflectsFlag() {
        User celebrity = createUser("celebrity", "Celebrity User", "celebrity@example.com");
        userRepository.setCelebrity(celebrity.getId(), true);

        assertThat(followService.isCelebrity(celebrity.getId())).isTrue();
        assertThat(followService.isCelebrity(user1.getId())).isFalse();
    }

    private User createUser(String username, String displayName, String email) {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername(username);
        req.setDisplayName(displayName);
        req.setEmail(email);
        req.setBio("Bio for " + username);
        return userService.createUser(req);
    }
}
