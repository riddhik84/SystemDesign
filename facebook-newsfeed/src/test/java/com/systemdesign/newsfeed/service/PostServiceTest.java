package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.cache.FeedCacheService;
import com.systemdesign.newsfeed.cache.PostCacheService;
import com.systemdesign.newsfeed.dto.CreatePostRequest;
import com.systemdesign.newsfeed.dto.CreateUserRequest;
import com.systemdesign.newsfeed.dto.PostResponse;
import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.PostRepository;
import com.systemdesign.newsfeed.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostService tests: creation + validation, engagement counters (likes/comments) that drive the
 * ranking signals, cache-aside reads, and the fanout event trigger.
 *
 * FanoutService is mocked so the async fanout does not run (and the event listener call can be
 * verified directly); PostCacheService / FeedCacheService are mocked so Redis is not required.
 * Real JPA repositories run on H2 under the "test" profile.
 */
@SpringBootTest
@ActiveProfiles("test")
class PostServiceTest {

    @Autowired
    private PostService postService;

    @Autowired
    private UserService userService;

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

    private User testUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        postRepository.deleteAll();
        userRepository.deleteAll();

        when(postCacheService.get(anyString())).thenReturn(Optional.empty());
        when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        testUser = createUser("poster", "Post User", "poster@example.com");
        otherUser = createUser("other", "Other User", "other@example.com");
    }

    @Test
    void createTextPostSucceeds() {
        PostResponse response = createPost(testUser.getId(), "My first post!");

        assertThat(response.getId()).isNotNull();
        assertThat(response.getAuthorId()).isEqualTo(testUser.getId());
        assertThat(response.getAuthorUsername()).isEqualTo("poster");
        assertThat(response.getContent()).isEqualTo("My first post!");
        assertThat(response.getMediaUrl()).isNull();
        assertThat(response.getLikeCount()).isEqualTo(0);
        assertThat(response.getCommentCount()).isEqualTo(0);
        assertThat(response.getCreatedAt()).isNotNull();

        User updated = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(updated.getPostCount()).isEqualTo(1);
    }

    @Test
    void createPostWithMediaUrlSucceeds() {
        String mediaUrl = "https://cdn.newsfeed.example.com/img/xyz.jpg";

        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(testUser.getId());
        req.setContent("Post with media");
        req.setMediaUrl(mediaUrl);
        PostResponse response = postService.createPost(req);

        assertThat(response.getMediaUrl()).isEqualTo(mediaUrl);

        Post saved = postRepository.findById(response.getId()).orElseThrow();
        assertThat(saved.getMediaUrl()).isEqualTo(mediaUrl);
    }

    @Test
    void createPostIncrementsUserPostCount() {
        createPost(testUser.getId(), "Post 1");
        createPost(testUser.getId(), "Post 2");

        User updated = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(updated.getPostCount()).isEqualTo(2);
    }

    @Test
    void createPostWithNonExistentAuthorThrowsNoSuchElement() {
        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId("nonexistent-user-id");
        req.setContent("Ghost post");

        assertThatThrownBy(() -> postService.createPost(req))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void createPostCachesPostResponse() {
        createPost(testUser.getId(), "Cached post");

        verify(postCacheService, times(1)).put(any(PostResponse.class));
    }

    @Test
    void createPostTriggersFanoutEvent() {
        createPost(testUser.getId(), "Fanout post");

        // Fanout is triggered via the AFTER_COMMIT event listener (onPostCreated).
        verify(fanoutService, times(1)).onPostCreated(any());
    }

    @Test
    void getPostReturnsPost() {
        PostResponse created = createPost(testUser.getId(), "Readable post");

        PostResponse retrieved = postService.getPost(created.getId());

        assertThat(retrieved.getId()).isEqualTo(created.getId());
        assertThat(retrieved.getAuthorId()).isEqualTo(testUser.getId());
        assertThat(retrieved.getAuthorUsername()).isEqualTo("poster");
        assertThat(retrieved.getContent()).isEqualTo("Readable post");
    }

    @Test
    void getPostForMissingIdThrowsNoSuchElement() {
        assertThatThrownBy(() -> postService.getPost("nonexistent-post-id"))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void likePostIncrementsLikeCountAndEvictsCache() {
        PostResponse created = createPost(testUser.getId(), "Like me");

        PostResponse liked = postService.likePost(created.getId());

        assertThat(liked.getLikeCount()).isEqualTo(1);

        Post saved = postRepository.findById(created.getId()).orElseThrow();
        assertThat(saved.getLikeCount()).isEqualTo(1);

        verify(postCacheService).evict(created.getId());
    }

    @Test
    void commentPostIncrementsCommentCount() {
        PostResponse created = createPost(testUser.getId(), "Comment me");

        PostResponse commented = postService.commentPost(created.getId());

        assertThat(commented.getCommentCount()).isEqualTo(1);

        Post saved = postRepository.findById(created.getId()).orElseThrow();
        assertThat(saved.getCommentCount()).isEqualTo(1);

        verify(postCacheService).evict(created.getId());
    }

    @Test
    void getRecentPostsByAuthorsReturnsOrdered() {
        createPost(testUser.getId(), "Post 1");
        createPost(testUser.getId(), "Post 2");
        createPost(otherUser.getId(), "Other post");

        LocalDateTime since = LocalDateTime.now().minusHours(1);
        List<Post> posts = postService.getRecentPostsByAuthors(
            List.of(testUser.getId(), otherUser.getId()), since, 10);

        assertThat(posts).hasSize(3);
        // Reverse-chronological: most recent first.
        assertThat(posts.get(0).getContent()).isEqualTo("Other post");
        assertThat(posts.get(1).getContent()).isEqualTo("Post 2");
        assertThat(posts.get(2).getContent()).isEqualTo("Post 1");
    }

    @Test
    void getRecentPostsByAuthorsFiltersOldPosts() {
        createPost(testUser.getId(), "Recent post");

        // Query for posts created after a future instant -> excludes everything.
        LocalDateTime future = LocalDateTime.now().plusHours(1);
        List<Post> posts = postService.getRecentPostsByAuthors(
            List.of(testUser.getId()), future, 10);

        assertThat(posts).isEmpty();
    }

    @Test
    void getRecentPostsByAuthorsRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            createPost(testUser.getId(), "Post " + i);
        }

        LocalDateTime since = LocalDateTime.now().minusHours(1);
        List<Post> posts = postService.getRecentPostsByAuthors(
            List.of(testUser.getId()), since, 3);

        assertThat(posts).hasSize(3);
    }

    @Test
    void getRecentPostsByAuthorsReturnsEmptyForEmptyAuthors() {
        List<Post> posts = postService.getRecentPostsByAuthors(
            List.of(), LocalDateTime.now().minusHours(1), 10);

        assertThat(posts).isEmpty();
    }

    private PostResponse createPost(String authorId, String content) {
        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(authorId);
        req.setContent(content);
        return postService.createPost(req);
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
