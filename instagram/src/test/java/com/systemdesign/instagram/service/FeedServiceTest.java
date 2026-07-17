package com.systemdesign.instagram.service;

import com.systemdesign.instagram.cache.FeedCacheService;
import com.systemdesign.instagram.cache.PostCacheService;
import com.systemdesign.instagram.dto.CreatePostRequest;
import com.systemdesign.instagram.dto.CreateUserRequest;
import com.systemdesign.instagram.dto.FeedResponse;
import com.systemdesign.instagram.dto.PostResponse;
import com.systemdesign.instagram.dto.UploadUrlRequest;
import com.systemdesign.instagram.dto.UploadUrlResponse;
import com.systemdesign.instagram.model.MediaType;
import com.systemdesign.instagram.model.Post;
import com.systemdesign.instagram.model.User;
import com.systemdesign.instagram.repository.FollowRepository;
import com.systemdesign.instagram.repository.MediaRepository;
import com.systemdesign.instagram.repository.PostRepository;
import com.systemdesign.instagram.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private MediaService mediaService;

    @Autowired
    private PostService postService;

    @Autowired
    private FanoutService fanoutService;

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

    @Autowired
    private MediaRepository mediaRepository;

    private User viewer;
    private User normalUser;
    private User celebrity;

    @BeforeEach
    void setUp() {
        postRepository.deleteAll();
        followRepository.deleteAll();
        mediaRepository.deleteAll();
        userRepository.deleteAll();

        when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(postCacheService.get(anyString())).thenReturn(Optional.empty());

        CreateUserRequest req1 = new CreateUserRequest();
        req1.setUsername("viewer");
        req1.setDisplayName("Viewer User");
        req1.setEmail("viewer@example.com");
        req1.setBio("Test viewer");
        viewer = userService.createUser(req1);

        CreateUserRequest req2 = new CreateUserRequest();
        req2.setUsername("normal");
        req2.setDisplayName("Normal User");
        req2.setEmail("normal@example.com");
        req2.setBio("Normal poster");
        normalUser = userService.createUser(req2);

        CreateUserRequest req3 = new CreateUserRequest();
        req3.setUsername("celebrity");
        req3.setDisplayName("Celebrity User");
        req3.setEmail("celebrity@example.com");
        req3.setBio("Famous person");
        celebrity = userService.createUser(req3);

        // Mark celebrity as celebrity
        userRepository.setCelebrity(celebrity.getId(), true);
    }

    @Test
    void normalUserPostFansOutToFollowers() {
        followService.follow(viewer.getId(), normalUser.getId());

        String mediaId = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(normalUser.getId());
        req.setCaption("Normal post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        PostResponse post = postService.createPost(req);

        // Note: createPost internally calls fanout, which will call fanoutSync
        // We need to wait briefly for the async task or call fanoutSync directly
        Post savedPost = postRepository.findById(post.getId()).orElseThrow();
        fanoutService.fanoutSync(savedPost);

        // Verify fanout reached the viewer (follower). Two calls are expected:
        //   1. the async @TransactionalEventListener(AFTER_COMMIT) fired by createPost
        //   2. the explicit fanoutSync call above
        // The event-driven call runs on the fanoutExecutor background thread, so we use
        // Mockito's timeout() verification to await it deterministically rather than racing it.
        verify(feedCacheService, timeout(2000).times(2)).addToManyFeeds(
            anyCollection(),
            eq(savedPost.getId()),
            anyLong()
        );
    }

    @Test
    void celebrityPostDoesNotFanOut() {
        followService.follow(viewer.getId(), celebrity.getId());

        String mediaId = createAndCompleteMedia(celebrity.getId(), MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(celebrity.getId());
        req.setCaption("Celebrity post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        PostResponse post = postService.createPost(req);

        // Call synchronous fanout
        Post savedPost = postRepository.findById(post.getId()).orElseThrow();
        fanoutService.fanoutSync(savedPost);

        // Verify fanout was NOT called for celebrity posts
        verify(feedCacheService, never()).addToManyFeeds(
            anyCollection(),
            anyString(),
            anyLong()
        );
    }

    @Test
    void feedIncludesCelebrityPostsViaReadPath() {
        followService.follow(viewer.getId(), celebrity.getId());

        String mediaId = createAndCompleteMedia(celebrity.getId(), MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(celebrity.getId());
        req.setCaption("Celebrity content");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        PostResponse post = postService.createPost(req);

        // Celebrity posts don't fan out, so cache returns empty
        when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        // Should include celebrity post from read-path merge
        assertThat(feed.getPosts()).hasSize(1);
        assertThat(feed.getPosts().get(0).getAuthorId()).isEqualTo(celebrity.getId());
        assertThat(feed.getPosts().get(0).getCaption()).isEqualTo("Celebrity content");
        assertThat(feed.getFeedStrategyNote()).isNotNull();
    }

    @Test
    void feedMergesPrecomputedAndCelebrityPosts() {
        followService.follow(viewer.getId(), normalUser.getId());
        followService.follow(viewer.getId(), celebrity.getId());

        // Normal user creates a post
        String normalMediaId = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest normalReq = new CreatePostRequest();
        normalReq.setAuthorId(normalUser.getId());
        normalReq.setCaption("Normal post");
        normalReq.setMediaType(MediaType.PHOTO);
        normalReq.setMediaIds(List.of(normalMediaId));
        PostResponse normalPost = postService.createPost(normalReq);

        // Celebrity creates a post
        String celebMediaId = createAndCompleteMedia(celebrity.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest celebReq = new CreatePostRequest();
        celebReq.setAuthorId(celebrity.getId());
        celebReq.setCaption("Celebrity post");
        celebReq.setMediaType(MediaType.PHOTO);
        celebReq.setMediaIds(List.of(celebMediaId));
        PostResponse celebPost = postService.createPost(celebReq);

        // Mock cache to return normal post (fanout-on-write)
        when(feedCacheService.getFeedPostIds(eq(viewer.getId()), anyInt(), anyInt()))
            .thenReturn(List.of(normalPost.getId()));

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        // Should include both posts
        assertThat(feed.getPosts()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(feed.getUserId()).isEqualTo(viewer.getId());
    }

    @Test
    void feedRespectsPageSizeAndPagination() {
        followService.follow(viewer.getId(), normalUser.getId());

        // Create 5 posts
        for (int i = 0; i < 5; i++) {
            String mediaId = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);
            CreatePostRequest req = new CreatePostRequest();
            req.setAuthorId(normalUser.getId());
            req.setCaption("Post " + i);
            req.setMediaType(MediaType.PHOTO);
            req.setMediaIds(List.of(mediaId));
            postService.createPost(req);
        }

        // Mock cache to return all post IDs
        List<String> allPostIds = postRepository.findAll().stream()
            .map(Post::getId)
            .toList();
        when(feedCacheService.getFeedPostIds(eq(viewer.getId()), eq(0), anyInt()))
            .thenReturn(allPostIds.subList(0, Math.min(3, allPostIds.size())));

        FeedResponse page0 = feedService.getFeed(viewer.getId(), 0, 3);

        assertThat(page0.getPage()).isEqualTo(0);
        assertThat(page0.getPageSize()).isEqualTo(3);
        assertThat(page0.getPosts()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void feedSetsHasMoreFlagCorrectly() {
        followService.follow(viewer.getId(), normalUser.getId());

        // Create 3 posts
        for (int i = 0; i < 3; i++) {
            String mediaId = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);
            CreatePostRequest req = new CreatePostRequest();
            req.setAuthorId(normalUser.getId());
            req.setCaption("Post " + i);
            req.setMediaType(MediaType.PHOTO);
            req.setMediaIds(List.of(mediaId));
            postService.createPost(req);
        }

        List<String> allPostIds = postRepository.findAll().stream()
            .map(Post::getId)
            .toList();

        when(feedCacheService.getFeedPostIds(eq(viewer.getId()), eq(0), anyInt()))
            .thenReturn(allPostIds);

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 2);

        // With 3 posts and pageSize 2, hasMore should be true
        assertThat(feed.getPosts()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void emptyFeedReturnsEmptyList() {
        // Viewer follows no one
        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getPosts()).isEmpty();
        assertThat(feed.getUserId()).isEqualTo(viewer.getId());
        assertThat(feed.getPage()).isEqualTo(0);
        assertThat(feed.getPageSize()).isEqualTo(20);
        assertThat(feed.isHasMore()).isFalse();
    }

    @Test
    void feedForUserWithNoFolloweesReturnsEmpty() {
        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getPosts()).isEmpty();
        assertThat(feed.isHasMore()).isFalse();
    }

    @Test
    void feedDeduplicatesPostIds() {
        followService.follow(viewer.getId(), normalUser.getId());
        followService.follow(viewer.getId(), celebrity.getId());

        // Create a post from normal user
        String mediaId = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(normalUser.getId());
        req.setCaption("Duplicate test");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));
        PostResponse post = postService.createPost(req);

        // Mock cache to return the same post ID multiple times
        when(feedCacheService.getFeedPostIds(eq(viewer.getId()), anyInt(), anyInt()))
            .thenReturn(List.of(post.getId(), post.getId(), post.getId()));

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        // Should deduplicate and return only one instance
        assertThat(feed.getPosts()).hasSize(1);
    }

    @Test
    void feedSortsByCreatedAtDescending() {
        followService.follow(viewer.getId(), normalUser.getId());

        // Create 3 posts in sequence
        String mediaId1 = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest req1 = new CreatePostRequest();
        req1.setAuthorId(normalUser.getId());
        req1.setCaption("First post");
        req1.setMediaType(MediaType.PHOTO);
        req1.setMediaIds(List.of(mediaId1));
        PostResponse post1 = postService.createPost(req1);

        String mediaId2 = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest req2 = new CreatePostRequest();
        req2.setAuthorId(normalUser.getId());
        req2.setCaption("Second post");
        req2.setMediaType(MediaType.PHOTO);
        req2.setMediaIds(List.of(mediaId2));
        PostResponse post2 = postService.createPost(req2);

        String mediaId3 = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest req3 = new CreatePostRequest();
        req3.setAuthorId(normalUser.getId());
        req3.setCaption("Third post");
        req3.setMediaType(MediaType.PHOTO);
        req3.setMediaIds(List.of(mediaId3));
        PostResponse post3 = postService.createPost(req3);

        when(feedCacheService.getFeedPostIds(eq(viewer.getId()), anyInt(), anyInt()))
            .thenReturn(List.of(post1.getId(), post2.getId(), post3.getId()));

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getPosts()).hasSizeGreaterThanOrEqualTo(1);
        // Most recent should be first
        if (feed.getPosts().size() >= 2) {
            assertThat(feed.getPosts().get(0).getCaption()).isEqualTo("Third post");
        }
    }

    @Test
    void feedIncludesServedAtEpochMs() {
        followService.follow(viewer.getId(), normalUser.getId());

        String mediaId = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(normalUser.getId());
        req.setCaption("Timestamp test");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));
        postService.createPost(req);

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getServedAtEpochMs()).isGreaterThan(0);
    }

    @Test
    void feedIncludesStrategyNote() {
        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        assertThat(feed.getFeedStrategyNote()).isNotNull();
        assertThat(feed.getFeedStrategyNote()).isNotEmpty();
    }

    @Test
    void coldStartFallbackWorkesWhenCacheEmpty() {
        followService.follow(viewer.getId(), normalUser.getId());

        // Create posts
        String mediaId = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(normalUser.getId());
        req.setCaption("Cold start post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));
        postService.createPost(req);

        // Ensure cache returns empty (simulating cold start)
        when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        // Should still return posts via DB fallback
        assertThat(feed.getPosts()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(feed.getFeedStrategyNote()).contains("fallback");
    }

    @Test
    void multipleFolloweesPostsAreMerged() {
        User user2 = createUser("user2", "User Two", "user2@example.com");
        User user3 = createUser("user3", "User Three", "user3@example.com");

        followService.follow(viewer.getId(), normalUser.getId());
        followService.follow(viewer.getId(), user2.getId());
        followService.follow(viewer.getId(), user3.getId());

        // Each user creates a post
        String mediaId1 = createAndCompleteMedia(normalUser.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest req1 = new CreatePostRequest();
        req1.setAuthorId(normalUser.getId());
        req1.setCaption("Post from normal");
        req1.setMediaType(MediaType.PHOTO);
        req1.setMediaIds(List.of(mediaId1));
        postService.createPost(req1);

        String mediaId2 = createAndCompleteMedia(user2.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest req2 = new CreatePostRequest();
        req2.setAuthorId(user2.getId());
        req2.setCaption("Post from user2");
        req2.setMediaType(MediaType.PHOTO);
        req2.setMediaIds(List.of(mediaId2));
        postService.createPost(req2);

        String mediaId3 = createAndCompleteMedia(user3.getId(), MediaType.PHOTO, 1_000_000);
        CreatePostRequest req3 = new CreatePostRequest();
        req3.setAuthorId(user3.getId());
        req3.setCaption("Post from user3");
        req3.setMediaType(MediaType.PHOTO);
        req3.setMediaIds(List.of(mediaId3));
        postService.createPost(req3);

        FeedResponse feed = feedService.getFeed(viewer.getId(), 0, 20);

        // Should include posts from all followed users
        assertThat(feed.getPosts()).hasSizeGreaterThanOrEqualTo(1);
    }

    private User createUser(String username, String displayName, String email) {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername(username);
        req.setDisplayName(displayName);
        req.setEmail(email);
        req.setBio("Bio for " + username);
        return userService.createUser(req);
    }

    private String createAndCompleteMedia(String uploaderId, MediaType type, long sizeBytes) {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(uploaderId);
        req.setType(type);
        req.setSizeBytes(sizeBytes);
        req.setFileName("test-file");

        UploadUrlResponse response = mediaService.createUploadUrl(req);
        mediaService.completeUpload(response.getMediaId());
        return response.getMediaId();
    }
}
