package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.cache.FeedCacheService;
import com.systemdesign.newsfeed.cache.PostCacheService;
import com.systemdesign.newsfeed.dto.CreatePostRequest;
import com.systemdesign.newsfeed.dto.CreateUserRequest;
import com.systemdesign.newsfeed.dto.PostResponse;
import com.systemdesign.newsfeed.model.Post;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FanoutService tests: normal-user posts fan out to follower feeds; celebrity posts and
 * follower-less authors are skipped.
 */
@SpringBootTest
@ActiveProfiles("test")
class FanoutServiceTest {

    @Autowired
    private FanoutService fanoutService;

    @Autowired
    private PostService postService;

    @Autowired
    private UserService userService;

    @Autowired
    private FollowService followService;

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
    private User author;

    @BeforeEach
    void setUp() {
        postRepository.deleteAll();
        followRepository.deleteAll();
        userRepository.deleteAll();

        when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(postCacheService.get(anyString())).thenReturn(Optional.empty());

        viewer = createUser("viewer", "Viewer User", "viewer@example.com");
        author = createUser("author", "Author User", "author@example.com");
    }

    @Test
    void normalUserPostFansOutToFollowers() {
        followService.follow(viewer.getId(), author.getId());

        PostResponse post = createPost(author.getId(), "Normal post");
        Post saved = postRepository.findById(post.getId()).orElseThrow();

        fanoutService.fanoutSync(saved);

        // Two calls expected for this post id:
        //   1. the async @TransactionalEventListener(AFTER_COMMIT) fired by createPost
        //   2. the explicit fanoutSync call above
        verify(feedCacheService, timeout(2000).times(2))
            .addToManyFeeds(anyCollection(), eq(saved.getId()), anyLong());
    }

    @Test
    void celebrityPostDoesNotFanOut() {
        followService.follow(viewer.getId(), author.getId());
        userRepository.setCelebrity(author.getId(), true);

        PostResponse post = createPost(author.getId(), "Celebrity post");
        Post saved = postRepository.findById(post.getId()).orElseThrow();

        fanoutService.fanoutSync(saved);

        verify(feedCacheService, never()).addToManyFeeds(anyCollection(), anyString(), anyLong());
    }

    @Test
    void noFollowersIsNoOp() {
        PostResponse post = createPost(author.getId(), "Lonely post");
        Post saved = postRepository.findById(post.getId()).orElseThrow();

        fanoutService.fanoutSync(saved);

        verify(feedCacheService, never()).addToManyFeeds(anyCollection(), anyString(), anyLong());
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
