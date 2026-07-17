package com.systemdesign.instagram.config;

import com.systemdesign.instagram.dto.*;
import com.systemdesign.instagram.model.MediaType;
import com.systemdesign.instagram.model.User;
import com.systemdesign.instagram.repository.UserRepository;
import com.systemdesign.instagram.service.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads sample data on startup for local development and testing.
 * Creates users, follows, media uploads, and posts to demonstrate the feed.
 */
@Component
@Profile("!test")
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserService userService;
    private final FollowService followService;
    private final MediaService mediaService;
    private final PostService postService;
    private final UserRepository userRepository;

    public DataInitializer(UserService userService,
                           FollowService followService,
                           MediaService mediaService,
                           PostService postService,
                           UserRepository userRepository) {
        this.userService = userService;
        this.followService = followService;
        this.mediaService = mediaService;
        this.postService = postService;
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void init() {
        try {
            // Create users
            User alice = createUser("alice", "Alice Chen", "alice@example.com", "Photography enthusiast 📷");
            User bob = createUser("bob", "Bob Martinez", "bob@example.com", "Travel blogger ✈️");
            User carol = createUser("carol", "Carol Kim", "carol@example.com", "Food lover 🍕");
            User david = createUser("david", "David Lee", "david@example.com", "Tech geek 💻");
            User celebrity = createUser("celebrity", "Famous Person", "famous@example.com", "Verified celebrity ⭐");

            // Make celebrity account by giving it many followers (simulate crossing threshold)
            // We'll manually set celebrity=true and high follower count
            userRepository.setCelebrity(celebrity.getId(), true);
            userRepository.incrementFollowerCount(celebrity.getId(), 100001L);

            // Set up follow relationships
            // Alice follows Bob, Carol, and Celebrity
            followService.follow(alice.getId(), bob.getId());
            followService.follow(alice.getId(), carol.getId());
            followService.follow(alice.getId(), celebrity.getId());

            // Bob follows Alice and Celebrity
            followService.follow(bob.getId(), alice.getId());
            followService.follow(bob.getId(), celebrity.getId());

            // Carol follows Alice, Bob, and David
            followService.follow(carol.getId(), alice.getId());
            followService.follow(carol.getId(), bob.getId());
            followService.follow(carol.getId(), david.getId());

            // David follows everyone
            followService.follow(david.getId(), alice.getId());
            followService.follow(david.getId(), bob.getId());
            followService.follow(david.getId(), carol.getId());
            followService.follow(david.getId(), celebrity.getId());

            // Create some posts with media
            createPostWithMedia(alice.getId(), MediaType.PHOTO, "Beautiful sunset at the beach 🌅", 1024000L);
            createPostWithMedia(bob.getId(), MediaType.PHOTO, "Exploring the streets of Tokyo", 2048000L);
            createPostWithMedia(carol.getId(), MediaType.PHOTO, "Best pizza I've ever had!", 1536000L);
            createPostWithMedia(david.getId(), MediaType.PHOTO, "New tech setup 💻", 2560000L);
            createPostWithMedia(celebrity.getId(), MediaType.PHOTO, "Behind the scenes from today's shoot", 3072000L);

            // Alice posts another photo
            createPostWithMedia(alice.getId(), MediaType.PHOTO, "Morning coffee ☕", 1024000L);

            // Bob posts a video
            createPostWithMedia(bob.getId(), MediaType.VIDEO, "Quick tour of my favorite cafe", 10485760L);

            log.info("Sample data initialized: 5 users, {} follows, 7 posts",
                followService.getFolloweeIds(alice.getId()).size() +
                followService.getFolloweeIds(bob.getId()).size() +
                followService.getFolloweeIds(carol.getId()).size() +
                followService.getFolloweeIds(david.getId()).size());

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

    private void createPostWithMedia(String authorId, MediaType mediaType, String caption, long sizeBytes) {
        try {
            // Request upload URL
            UploadUrlRequest uploadReq = new UploadUrlRequest();
            uploadReq.setUploaderId(authorId);
            uploadReq.setType(mediaType);
            uploadReq.setSizeBytes(sizeBytes);
            uploadReq.setFileName("sample." + (mediaType == MediaType.VIDEO ? "mp4" : "jpg"));
            UploadUrlResponse uploadResp = mediaService.createUploadUrl(uploadReq);

            // Simulate upload completion
            mediaService.completeUpload(uploadResp.getMediaId());

            // Create post
            CreatePostRequest postReq = new CreatePostRequest();
            postReq.setAuthorId(authorId);
            postReq.setCaption(caption);
            postReq.setMediaType(mediaType);
            postReq.setMediaIds(List.of(uploadResp.getMediaId()));
            postService.createPost(postReq);

        } catch (Exception e) {
            log.warn("Could not create sample post for user {}: {}", authorId, e.getMessage());
        }
    }
}
