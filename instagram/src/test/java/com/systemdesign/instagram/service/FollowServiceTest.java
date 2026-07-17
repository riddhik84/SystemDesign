package com.systemdesign.instagram.service;

import com.systemdesign.instagram.cache.FeedCacheService;
import com.systemdesign.instagram.cache.PostCacheService;
import com.systemdesign.instagram.dto.CreateUserRequest;
import com.systemdesign.instagram.model.Follow;
import com.systemdesign.instagram.model.User;
import com.systemdesign.instagram.repository.FollowRepository;
import com.systemdesign.instagram.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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

    @Value("${app.feed.celebrity-follower-threshold:100000}")
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
    void followSuccessfullyCreatesFollowRelationship() {
        followService.follow(user1.getId(), user2.getId());

        assertThat(followRepository.existsByFollowerIdAndFolloweeId(user1.getId(), user2.getId())).isTrue();

        User follower = userRepository.findById(user1.getId()).orElseThrow();
        User followee = userRepository.findById(user2.getId()).orElseThrow();

        assertThat(follower.getFollowingCount()).isEqualTo(1);
        assertThat(followee.getFollowerCount()).isEqualTo(1);
    }

    @Test
    void followIncrementsFollowerAndFollowingCounts() {
        followService.follow(user1.getId(), user2.getId());
        followService.follow(user1.getId(), user3.getId());
        followService.follow(user3.getId(), user2.getId());

        User alice = userRepository.findById(user1.getId()).orElseThrow();
        assertThat(alice.getFollowingCount()).isEqualTo(2);
        assertThat(alice.getFollowerCount()).isEqualTo(0);

        User bob = userRepository.findById(user2.getId()).orElseThrow();
        assertThat(bob.getFollowerCount()).isEqualTo(2);
        assertThat(bob.getFollowingCount()).isEqualTo(0);

        User charlie = userRepository.findById(user3.getId()).orElseThrow();
        assertThat(charlie.getFollowerCount()).isEqualTo(1);
        assertThat(charlie.getFollowingCount()).isEqualTo(1);
    }

    @Test
    void duplicateFollowThrowsIllegalStateException() {
        followService.follow(user1.getId(), user2.getId());

        assertThatThrownBy(() -> followService.follow(user1.getId(), user2.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already following");
    }

    @Test
    void selfFollowThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> followService.follow(user1.getId(), user1.getId()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot follow yourself");
    }

    @Test
    void followNonExistentUserThrowsNoSuchElementException() {
        assertThatThrownBy(() -> followService.follow(user1.getId(), "nonexistent-id"))
            .isInstanceOf(java.util.NoSuchElementException.class);

        assertThatThrownBy(() -> followService.follow("nonexistent-id", user2.getId()))
            .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void unfollowSuccessfullyRemovesFollowRelationship() {
        followService.follow(user1.getId(), user2.getId());
        followService.unfollow(user1.getId(), user2.getId());

        assertThat(followRepository.existsByFollowerIdAndFolloweeId(user1.getId(), user2.getId())).isFalse();

        User follower = userRepository.findById(user1.getId()).orElseThrow();
        User followee = userRepository.findById(user2.getId()).orElseThrow();

        assertThat(follower.getFollowingCount()).isEqualTo(0);
        assertThat(followee.getFollowerCount()).isEqualTo(0);
    }

    @Test
    void unfollowDecrementsFollowerAndFollowingCounts() {
        followService.follow(user1.getId(), user2.getId());
        followService.follow(user1.getId(), user3.getId());

        User alice = userRepository.findById(user1.getId()).orElseThrow();
        assertThat(alice.getFollowingCount()).isEqualTo(2);

        followService.unfollow(user1.getId(), user2.getId());

        alice = userRepository.findById(user1.getId()).orElseThrow();
        assertThat(alice.getFollowingCount()).isEqualTo(1);

        User bob = userRepository.findById(user2.getId()).orElseThrow();
        assertThat(bob.getFollowerCount()).isEqualTo(0);
    }

    @Test
    void unfollowNonExistentRelationshipDoesNothing() {
        followService.unfollow(user1.getId(), user2.getId());

        User alice = userRepository.findById(user1.getId()).orElseThrow();
        User bob = userRepository.findById(user2.getId()).orElseThrow();

        assertThat(alice.getFollowingCount()).isEqualTo(0);
        assertThat(bob.getFollowerCount()).isEqualTo(0);
    }

    @Test
    void getFolloweeIdsReturnsCorrectUsers() {
        followService.follow(user1.getId(), user2.getId());
        followService.follow(user1.getId(), user3.getId());

        List<String> followeeIds = followService.getFolloweeIds(user1.getId());

        assertThat(followeeIds).hasSize(2);
        assertThat(followeeIds).containsExactlyInAnyOrder(user2.getId(), user3.getId());
    }

    @Test
    void getFollowerIdsReturnsCorrectUsers() {
        followService.follow(user1.getId(), user2.getId());
        followService.follow(user3.getId(), user2.getId());

        List<String> followerIds = followService.getFollowerIds(user2.getId());

        assertThat(followerIds).hasSize(2);
        assertThat(followerIds).containsExactlyInAnyOrder(user1.getId(), user3.getId());
    }

    @Test
    void crossingCelebrityThresholdSetsCelebrityFlag() {
        User celebrity = createUser("celebrity", "Celebrity User", "celebrity@example.com");

        // Manually set follower count to just below threshold
        userRepository.incrementFollowerCount(celebrity.getId(), celebrityThreshold - 1);

        // One more follower should cross threshold
        User fan = createUser("fan", "Fan User", "fan@example.com");
        followService.follow(fan.getId(), celebrity.getId());

        User updatedCelebrity = userRepository.findById(celebrity.getId()).orElseThrow();
        assertThat(updatedCelebrity.getFollowerCount()).isEqualTo(celebrityThreshold);
        assertThat(updatedCelebrity.isCelebrity()).isTrue();
    }

    @Test
    void fallingBelowCelebrityThresholdClearsCelebrityFlag() {
        User celebrity = createUser("celebrity", "Celebrity User", "celebrity@example.com");

        // Set up celebrity with threshold + 1 followers (one manual increment + one actual follow)
        userRepository.incrementFollowerCount(celebrity.getId(), celebrityThreshold - 1);
        userRepository.setCelebrity(celebrity.getId(), true);

        User fan = createUser("fan", "Fan User", "fan@example.com");
        followService.follow(fan.getId(), celebrity.getId());
        // Now count = threshold

        // Now unfollow to go below threshold
        followService.unfollow(fan.getId(), celebrity.getId());
        // Now count = threshold - 1 (below threshold)

        User updatedCelebrity = userRepository.findById(celebrity.getId()).orElseThrow();
        assertThat(updatedCelebrity.getFollowerCount()).isEqualTo(celebrityThreshold - 1);
        assertThat(updatedCelebrity.isCelebrity()).isFalse();
    }

    @Test
    void isCelebrityReturnsTrueForCelebrityUser() {
        User celebrity = createUser("celebrity", "Celebrity User", "celebrity@example.com");
        userRepository.setCelebrity(celebrity.getId(), true);

        assertThat(followService.isCelebrity(celebrity.getId())).isTrue();
        assertThat(followService.isCelebrity(user1.getId())).isFalse();
    }

    @Test
    void emptyFolloweeListReturnsEmptyList() {
        List<String> followeeIds = followService.getFolloweeIds(user1.getId());
        assertThat(followeeIds).isEmpty();
    }

    @Test
    void emptyFollowerListReturnsEmptyList() {
        List<String> followerIds = followService.getFollowerIds(user1.getId());
        assertThat(followerIds).isEmpty();
    }

    private User createUser(String username, String displayName, String email) {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername(username);
        req.setDisplayName(displayName);
        req.setEmail(email);
        req.setBio("Test bio for " + username);
        return userService.createUser(req);
    }
}
