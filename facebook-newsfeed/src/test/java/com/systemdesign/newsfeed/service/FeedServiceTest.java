package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.cache.FeedCacheService;
import com.systemdesign.newsfeed.cache.PostCacheService;
import com.systemdesign.newsfeed.dto.CreatePostRequest;
import com.systemdesign.newsfeed.dto.CreateUserRequest;
import com.systemdesign.newsfeed.dto.FeedResponse;
import com.systemdesign.newsfeed.dto.PostResponse;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.FollowRepository;
import com.systemdesign.newsfeed.repository.PostRepository;
import com.systemdesign.newsfeed.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * FeedService tests: hybrid merge (fanout-on-write + fanout-on-read + cold-start fallback)
 * followed by the read-time ranking layer, on H2 with mocked Redis caches.
 */
@SpringBootTest
@ActiveProfiles("test")
class FeedServiceTest {

    @Autowired
    private FeedService feedService;

    @Autowired
    private UserService userService;

    @Autowired
    private FollowService followService;

    @Autowired
    private PostService postService;

    @MockBean
    private FeedCacheService feedCacheService;

    @MockBean
    private PostCacheService postCacheService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private FollowRepository followRepository;

    private User viewer;
    private User normalUser;
    private User celebrity;

    @BeforeEach
    void setUp() {
        postRepository.deleteAll();
        followRepository.deleteAll();
        userRepository.deleteAll();

        when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(postCacheService.get(anyString())).thenReturn(Optional.empty());

        viewer = createUser("viewer", "Viewer User", "viewer@example.com");
        normalUser = createUser("normal", "Normal User", "normal@example.com");
        celebrity = createUser("celebrity", "Celebrity User", "celebrity@example.com");

        // Mark celebrity as a celebrity (drives fanout-on-read)
        userRepository.setCelebrity(celebrity.getId(), true);
    }

    @Test
    void emptyFeedWhenFollowingNobody() {
        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getPosts()).isEmpty();
        assertThat(feed.getUserId()).isEqualTo(viewer.getId());
        assertThat(feed.isHasMore()).isFalse();
        assertThat(feed.getFeedStrategyNote()).contains("EMPTY_FEED");
    }

    @Test
    void coldStartFallbackWhenCacheEmpty() {
        followService.follow(viewer.getId(), normalUser.getId());
        createPost(normalUser.getId(), "Cold start post");

        // Cache is empty (default stub) -> cold start fallback path
        when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getPosts()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(feed.getFeedStrategyNote()).contains("fallback");
    }

    @Test
    void feedIncludesCelebrityPostsViaReadPath() {
        followService.follow(viewer.getId(), celebrity.getId());
        PostResponse celebPost = createPost(celebrity.getId(), "Celebrity content");

        // Celebrity posts are not fanned out on write, so the cache stays empty
        when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getPosts()).hasSize(1);
        assertThat(feed.getPosts().get(0).getId()).isEqualTo(celebPost.getId());
        assertThat(feed.getPosts().get(0).getAuthorId()).isEqualTo(celebrity.getId());
        assertThat(feed.getPosts().get(0).getContent()).isEqualTo("Celebrity content");
    }

    @Test
    void feedMergesPrecomputedAndCelebrityPosts() {
        followService.follow(viewer.getId(), normalUser.getId());
        followService.follow(viewer.getId(), celebrity.getId());

        PostResponse normalPost = createPost(normalUser.getId(), "Normal post");
        PostResponse celebPost = createPost(celebrity.getId(), "Celebrity post");

        // Fanout-on-write provided only the normal post; celebrity comes from the read path
        when(feedCacheService.getFeedPostIds(eq(viewer.getId()), anyInt(), anyInt()))
            .thenReturn(List.of(normalPost.getId()));

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        List<String> ids = feed.getPosts().stream().map(PostResponse::getId).toList();
        assertThat(feed.getPosts()).hasSize(2);
        assertThat(ids).containsExactlyInAnyOrder(normalPost.getId(), celebPost.getId());
    }

    @Test
    void feedDeduplicatesPostIds() {
        followService.follow(viewer.getId(), normalUser.getId());
        PostResponse post = createPost(normalUser.getId(), "Duplicate test");

        // Cache returns the same id three times
        when(feedCacheService.getFeedPostIds(eq(viewer.getId()), anyInt(), anyInt()))
            .thenReturn(List.of(post.getId(), post.getId(), post.getId()));

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getPosts()).hasSize(1);
    }

    @Test
    void rankingOrdersHigherEngagementFirst() {
        followService.follow(viewer.getId(), normalUser.getId());

        PostResponse postA = createPost(normalUser.getId(), "A");
        PostResponse postB = createPost(normalUser.getId(), "B");

        // Bump B's engagement so it outranks A despite near-equal age
        for (int i = 0; i < 5; i++) {
            postService.likePost(postB.getId());
        }
        for (int i = 0; i < 3; i++) {
            postService.commentPost(postB.getId());
        }

        when(feedCacheService.getFeedPostIds(eq(viewer.getId()), anyInt(), anyInt()))
            .thenReturn(List.of(postA.getId(), postB.getId()));

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getPosts()).hasSize(2);
        assertThat(feed.getPosts().get(0).getId()).isEqualTo(postB.getId());
        assertThat(feed.getPosts().get(0).getContent()).isEqualTo("B");
        assertThat(feed.getPosts().get(0).getScore()).isNotNull();
        assertThat(feed.getPosts().get(1).getScore()).isNotNull();
        assertThat(feed.getPosts().get(0).getScore())
            .isGreaterThanOrEqualTo(feed.getPosts().get(1).getScore());
    }

    @Test
    void feedRespectsPageSizeAndPaginationAndHasMore() {
        followService.follow(viewer.getId(), normalUser.getId());

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(createPost(normalUser.getId(), "Post " + i).getId());
        }

        when(feedCacheService.getFeedPostIds(eq(viewer.getId()), anyInt(), anyInt())).thenReturn(ids);

        FeedResponse page0 = feedService.getFeed(viewer.getId(), 0, 2);
        assertThat(page0.getPage()).isEqualTo(0);
        assertThat(page0.getPageSize()).isEqualTo(2);
        assertThat(page0.getPosts()).hasSize(2);
        assertThat(page0.isHasMore()).isTrue();

        FeedResponse lastPage = feedService.getFeed(viewer.getId(), 2, 2);
        assertThat(lastPage.getPosts()).hasSize(1);
        assertThat(lastPage.isHasMore()).isFalse();
    }

    @Test
    void feedIncludesServedAtEpochMs() {
        followService.follow(viewer.getId(), normalUser.getId());
        createPost(normalUser.getId(), "Timestamp test");

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getServedAtEpochMs()).isGreaterThan(0);
    }

    @Test
    void feedIncludesStrategyNote() {
        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getFeedStrategyNote()).isNotNull();
        assertThat(feed.getFeedStrategyNote()).isNotEmpty();
    }

    private User createUser(String username, String displayName, String email) {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername(username);
        req.setDisplayName(displayName);
        req.setEmail(email);
        req.setBio("Bio for " + username);
        return userService.createUser(req);
    }

    private PostResponse createPost(String authorId, String content) {
        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(authorId);
        req.setContent(content);
        return postService.createPost(req);
    }
}
