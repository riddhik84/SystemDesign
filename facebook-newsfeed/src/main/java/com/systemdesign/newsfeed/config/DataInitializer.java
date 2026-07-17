package com.systemdesign.newsfeed.config;

import com.systemdesign.newsfeed.dto.CreatePostRequest;
import com.systemdesign.newsfeed.dto.CreateUserRequest;
import com.systemdesign.newsfeed.dto.PostResponse;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.UserRepository;
import com.systemdesign.newsfeed.service.FollowService;
import com.systemdesign.newsfeed.service.PostService;
import com.systemdesign.newsfeed.service.UserService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Loads sample data on startup for local development and demos.
 * Creates users, follow relationships, text posts (some with a media URL), and seeds
 * engagement so the read-time ranking layer is demonstrable.
 *
 * <p>Disabled under the {@code test} profile so tests start from a clean slate.
 * The whole routine is wrapped in a try/catch so a Redis-down environment still boots.
 */
@Component
@Profile("!test")
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final String SAMPLE_IMAGE_URL = "https://cdn.newsfeed.example.com/img/xyz.jpg";

    private final UserService userService;
    private final FollowService followService;
    private final PostService postService;
    private final UserRepository userRepository;

    public DataInitializer(UserService userService,
                           FollowService followService,
                           PostService postService,
                           UserRepository userRepository) {
        this.userService = userService;
        this.followService = followService;
        this.postService = postService;
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void init() {
        try {
            // 1. Create users.
            User alice = createUser("alice", "Alice Chen", "alice@example.com", "Photography enthusiast");
            User bob = createUser("bob", "Bob Martinez", "bob@example.com", "Travel blogger");
            User carol = createUser("carol", "Carol Kim", "carol@example.com", "Food lover");
            User david = createUser("david", "David Lee", "david@example.com", "Tech geek");
            User celebrity = createUser("celebrity", "Famous Person", "famous@example.com", "Verified celebrity");

            // 2. Promote the celebrity account above the celebrity-follower-threshold (100000)
            //    so its posts are served via the fanout-on-read path.
            userRepository.setCelebrity(celebrity.getId(), true);
            userRepository.incrementFollowerCount(celebrity.getId(), 100001L);

            // 3. Follow relationships so feeds are non-empty and celebrity fanout-on-read is exercised.
            // Alice follows Bob, Carol, and Celebrity.
            followService.follow(alice.getId(), bob.getId());
            followService.follow(alice.getId(), carol.getId());
            followService.follow(alice.getId(), celebrity.getId());

            // Bob follows Alice and Celebrity.
            followService.follow(bob.getId(), alice.getId());
            followService.follow(bob.getId(), celebrity.getId());

            // Carol follows Alice, Bob, and David.
            followService.follow(carol.getId(), alice.getId());
            followService.follow(carol.getId(), bob.getId());
            followService.follow(carol.getId(), david.getId());

            // David follows everyone.
            followService.follow(david.getId(), alice.getId());
            followService.follow(david.getId(), bob.getId());
            followService.follow(david.getId(), carol.getId());
            followService.follow(david.getId(), celebrity.getId());

            // 4. Create posts: a mix of text-only (mediaUrl == null) and posts with a sample media URL,
            //    including a couple from the celebrity.
            createTextPost(alice.getId(), "Beautiful sunset at the beach", SAMPLE_IMAGE_URL);
            PostResponse bobHotPost = createTextPost(bob.getId(), "Exploring the streets of Tokyo", null);
            createTextPost(carol.getId(), "Best pizza I've ever had!", SAMPLE_IMAGE_URL);
            createTextPost(david.getId(), "New tech setup for the home office", null);
            createTextPost(celebrity.getId(), "Behind the scenes from today's shoot", SAMPLE_IMAGE_URL);
            createTextPost(alice.getId(), "Morning coffee before the shoot", null);
            createTextPost(bob.getId(), "Quick tour of my favorite cafe", null);
            createTextPost(celebrity.getId(), "Big announcement coming soon", null);

            // 5. Seed engagement so ranking is demonstrable: push Bob's Tokyo post to ~50 likes /
            //    10 comments while leaving the others low. Higher engagement should rank higher.
            for (int i = 0; i < 50; i++) {
                postService.likePost(bobHotPost.getId());
            }
            for (int i = 0; i < 10; i++) {
                postService.commentPost(bobHotPost.getId());
            }

            // 6. Summary line.
            long followCount = followService.getFolloweeIds(alice.getId()).size()
                + followService.getFolloweeIds(bob.getId()).size()
                + followService.getFolloweeIds(carol.getId()).size()
                + followService.getFolloweeIds(david.getId()).size();
            log.info("Sample data initialized: 5 users, {} follows, 8 posts (Bob's Tokyo post seeded with ~50 likes / 10 comments)",
                followCount);

        } catch (Exception e) {
            log.error("Error initializing sample data", e);
        }
    }

    private User createUser(String username, String displayName, String email, String bio) {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername(username);
        req.setDisplayName(displayName);
        req.setEmail(email);
        req.setBio(bio);
        return userService.createUser(req);
    }

    private PostResponse createTextPost(String authorId, String content, String mediaUrl) {
        CreatePostRequest req = new CreatePostRequest();
        req.setAuthorId(authorId);
        req.setContent(content);
        req.setMediaUrl(mediaUrl);
        return postService.createPost(req);
    }
}
