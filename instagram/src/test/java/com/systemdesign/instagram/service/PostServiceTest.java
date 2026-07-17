package com.systemdesign.instagram.service;

import com.systemdesign.instagram.cache.FeedCacheService;
import com.systemdesign.instagram.cache.PostCacheService;
import com.systemdesign.instagram.dto.CreatePostRequest;
import com.systemdesign.instagram.dto.CreateUserRequest;
import com.systemdesign.instagram.dto.PostResponse;
import com.systemdesign.instagram.dto.UploadUrlRequest;
import com.systemdesign.instagram.dto.UploadUrlResponse;
import com.systemdesign.instagram.model.MediaType;
import com.systemdesign.instagram.model.Post;
import com.systemdesign.instagram.model.User;
import com.systemdesign.instagram.repository.MediaRepository;
import com.systemdesign.instagram.repository.PostRepository;
import com.systemdesign.instagram.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class PostServiceTest {

    @Autowired
    private PostService postService;

    @Autowired
    private UserService userService;

    @Autowired
    private MediaService mediaService;

    @MockBean
    private FanoutService fanoutService;

    @MockBean
    private PostCacheService postCacheService;

    @MockBean
    private FeedCacheService feedCacheService;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MediaRepository mediaRepository;

    private User testUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        postRepository.deleteAll();
        mediaRepository.deleteAll();
        userRepository.deleteAll();

        when(postCacheService.get(anyString())).thenReturn(Optional.empty());
        when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        CreateUserRequest req1 = new CreateUserRequest();
        req1.setUsername("poster");
        req1.setDisplayName("Post User");
        req1.setEmail("poster@example.com");
        req1.setBio("Test user for posts");
        testUser = userService.createUser(req1);

        CreateUserRequest req2 = new CreateUserRequest();
        req2.setUsername("other");
        req2.setDisplayName("Other User");
        req2.setEmail("other@example.com");
        req2.setBio("Another test user");
        otherUser = userService.createUser(req2);
    }

    @Test
    void createPostWithSinglePhotoSucceeds() {
        String mediaId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("My first post!");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        PostResponse response = postService.createPost(req);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getAuthorId()).isEqualTo(testUser.getId());
        assertThat(response.getAuthorUsername()).isEqualTo("poster");
        assertThat(response.getCaption()).isEqualTo("My first post!");
        assertThat(response.getMediaType()).isEqualTo("PHOTO");
        assertThat(response.getMediaUrls()).hasSize(1);
        assertThat(response.getCreatedAt()).isNotNull();

        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(updatedUser.getPostCount()).isEqualTo(1);
    }

    @Test
    void createPostIncrementsUserPostCount() {
        String mediaId1 = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);
        String mediaId2 = createAndCompleteMedia(MediaType.VIDEO, 10_000_000);

        CreatePostRequest req1 = new CreatePostRequest();
        req1.setAuthorId(testUser.getId());
        req1.setCaption("Post 1");
        req1.setMediaType(MediaType.PHOTO);
        req1.setMediaIds(List.of(mediaId1));
        postService.createPost(req1);

        CreatePostRequest req2 = new CreatePostRequest();
        req2.setAuthorId(testUser.getId());
        req2.setCaption("Post 2");
        req2.setMediaType(MediaType.VIDEO);
        req2.setMediaIds(List.of(mediaId2));
        postService.createPost(req2);

        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(updatedUser.getPostCount()).isEqualTo(2);
    }

    @Test
    void createPostWithMultipleMediaForCarousel() {
        String mediaId1 = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);
        String mediaId2 = createAndCompleteMedia(MediaType.PHOTO, 2_000_000);
        String mediaId3 = createAndCompleteMedia(MediaType.PHOTO, 1_500_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("Carousel post");
        req.setMediaType(MediaType.CAROUSEL);
        req.setMediaIds(List.of(mediaId1, mediaId2, mediaId3));

        PostResponse response = postService.createPost(req);

        assertThat(response.getMediaUrls()).hasSize(3);
        assertThat(response.getMediaType()).isEqualTo("CAROUSEL");
    }

    @Test
    void createPostWithNonExistentAuthorThrowsNoSuchElementException() {
        String mediaId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId("nonexistent-user-id");
        req.setCaption("Ghost post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        assertThatThrownBy(() -> postService.createPost(req))
            .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void createPostWithNonUploadedMediaThrowsIllegalStateException() {
        UploadUrlRequest uploadReq = new UploadUrlRequest();
        uploadReq.setUploaderId(testUser.getId());
        uploadReq.setType(MediaType.PHOTO);
        uploadReq.setSizeBytes(1_000_000);
        uploadReq.setFileName("pending.jpg");
        UploadUrlResponse uploadResp = mediaService.createUploadUrl(uploadReq);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("Post with pending media");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(uploadResp.getMediaId()));

        assertThatThrownBy(() -> postService.createPost(req))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not uploaded");
    }

    @Test
    void createPostCachesPostResponse() {
        String mediaId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("Cached post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        PostResponse response = postService.createPost(req);

        verify(postCacheService, times(1)).put(any(PostResponse.class));
    }

    @Test
    void createPostTriggersFanout() {
        String mediaId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("Fanout post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        PostResponse response = postService.createPost(req);

        // Fanout is now triggered via event listener (onPostCreated) instead of direct call
        // The event is published after transaction commit, so we verify the listener was called
        verify(fanoutService, times(1)).onPostCreated(any());
    }

    @Test
    void getPostReturnsCorrectPost() {
        String mediaId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("Test post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        PostResponse created = postService.createPost(req);

        PostResponse retrieved = postService.getPost(created.getId());

        assertThat(retrieved.getId()).isEqualTo(created.getId());
        assertThat(retrieved.getAuthorId()).isEqualTo(testUser.getId());
        assertThat(retrieved.getAuthorUsername()).isEqualTo("poster");
        assertThat(retrieved.getCaption()).isEqualTo("Test post");
    }

    @Test
    void getPostForNonExistentIdThrowsNoSuchElementException() {
        assertThatThrownBy(() -> postService.getPost("nonexistent-post-id"))
            .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void getPostUsesCache() {
        String mediaId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("Cached post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        PostResponse created = postService.createPost(req);

        // First call misses cache (mocked to return empty)
        postService.getPost(created.getId());
        verify(postCacheService, times(1)).get(created.getId());

        // Second call should also check cache
        postService.getPost(created.getId());
        verify(postCacheService, times(2)).get(created.getId());
    }

    @Test
    void getPostPopulatesCacheOnMiss() {
        String mediaId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("Cache miss post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        PostResponse created = postService.createPost(req);

        // Reset mock to clear the put from createPost
        // Get should trigger another put on cache miss
        postService.getPost(created.getId());

        // Verify put was called at least during creation
        verify(postCacheService, times(2)).put(any(PostResponse.class));
    }

    @Test
    void getRecentPostsByAuthorsReturnsPostsInOrder() {
        String mediaId1 = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);
        String mediaId2 = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);
        String mediaId3 = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        // Create posts from testUser
        CreatePostRequest req1 = new CreatePostRequest();
        req1.setAuthorId(testUser.getId());
        req1.setCaption("Post 1");
        req1.setMediaType(MediaType.PHOTO);
        req1.setMediaIds(List.of(mediaId1));
        postService.createPost(req1);

        CreatePostRequest req2 = new CreatePostRequest();
        req2.setAuthorId(testUser.getId());
        req2.setCaption("Post 2");
        req2.setMediaType(MediaType.PHOTO);
        req2.setMediaIds(List.of(mediaId2));
        postService.createPost(req2);

        // Create post from otherUser
        CreatePostRequest req3 = new CreatePostRequest();
        req3.setAuthorId(otherUser.getId());
        req3.setCaption("Other post");
        req3.setMediaType(MediaType.PHOTO);
        req3.setMediaIds(List.of(mediaId3));
        postService.createPost(req3);

        LocalDateTime since = LocalDateTime.now().minusHours(1);
        List<Post> posts = postService.getRecentPostsByAuthors(
            List.of(testUser.getId(), otherUser.getId()),
            since,
            10
        );

        assertThat(posts).hasSize(3);
        // Should be ordered by createdAt desc (most recent first)
        assertThat(posts.get(0).getCaption()).isEqualTo("Other post");
        assertThat(posts.get(1).getCaption()).isEqualTo("Post 2");
        assertThat(posts.get(2).getCaption()).isEqualTo("Post 1");
    }

    @Test
    void getRecentPostsByAuthorsFiltersOldPosts() {
        String mediaId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("Recent post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));
        postService.createPost(req);

        // Query for posts since 1 hour in the future (should exclude all)
        LocalDateTime futureTime = LocalDateTime.now().plusHours(1);
        List<Post> posts = postService.getRecentPostsByAuthors(
            List.of(testUser.getId()),
            futureTime,
            10
        );

        assertThat(posts).isEmpty();
    }

    @Test
    void getRecentPostsByAuthorsRespectsLimit() {
        // Create 5 posts
        for (int i = 0; i < 5; i++) {
            String mediaId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);
            CreatePostRequest req = new CreatePostRequest();
            req.setAuthorId(testUser.getId());
            req.setCaption("Post " + i);
            req.setMediaType(MediaType.PHOTO);
            req.setMediaIds(List.of(mediaId));
            postService.createPost(req);
        }

        LocalDateTime since = LocalDateTime.now().minusHours(1);
        List<Post> posts = postService.getRecentPostsByAuthors(
            List.of(testUser.getId()),
            since,
            3
        );

        assertThat(posts).hasSize(3);
    }

    @Test
    void toResponseResolvesAuthorUsername() {
        String mediaId = createAndCompleteMedia(MediaType.PHOTO, 1_000_000);

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("Username test");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of(mediaId));

        PostResponse response = postService.createPost(req);

        assertThat(response.getAuthorUsername()).isEqualTo("poster");
    }

    @Test
    void createPostWithEmptyMediaListStillCreatesPost() {
        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setCaption("Text-only post");
        req.setMediaType(MediaType.PHOTO);
        req.setMediaIds(List.of());

        PostResponse response = postService.createPost(req);

        assertThat(response.getMediaUrls()).isEmpty();
        assertThat(response.getCaption()).isEqualTo("Text-only post");
    }

    private String createAndCompleteMedia(MediaType type, long sizeBytes) {
        UploadUrlRequest req = new UploadUrlRequest();
        req.setUploaderId(testUser.getId());
        req.setType(type);
        req.setSizeBytes(sizeBytes);
        req.setFileName("test-file");

        UploadUrlResponse response = mediaService.createUploadUrl(req);
        mediaService.completeUpload(response.getMediaId());
        return response.getMediaId();
    }
}
