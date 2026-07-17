package com.systemdesign.instagram.service;

import com.systemdesign.instagram.cache.PostCacheService;
import com.systemdesign.instagram.dto.CreatePostRequest;
import com.systemdesign.instagram.dto.PostResponse;
import com.systemdesign.instagram.event.PostCreatedEvent;
import com.systemdesign.instagram.model.Post;
import com.systemdesign.instagram.model.User;
import com.systemdesign.instagram.repository.PostRepository;
import com.systemdesign.instagram.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Post creation and retrieval service.
 *
 * Responsibilities:
 * - Create posts with validated media
 * - Retrieve posts with cache-aside pattern
 * - Trigger fanout to followers' feeds
 * - Query recent posts by authors (for feed merge)
 *
 * Flow on createPost:
 *   1. Validate author exists
 *   2. Resolve media IDs to CDN URLs (validates upload completion)
 *   3. Save Post entity
 *   4. Increment author's post count
 *   5. Cache the post response
 *   6. Trigger async fanout (FanoutService)
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final MediaService mediaService;
    private final PostCacheService postCacheService;
    private final ApplicationEventPublisher eventPublisher;

    public PostService(PostRepository postRepository,
                       UserRepository userRepository,
                       UserService userService,
                       MediaService mediaService,
                       PostCacheService postCacheService,
                       ApplicationEventPublisher eventPublisher) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.mediaService = mediaService;
        this.postCacheService = postCacheService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Create a new post.
     * Validates author and media, saves the post, increments counts, caches, and triggers fanout.
     *
     * @param req post creation request
     * @return PostResponse DTO
     * @throws NoSuchElementException if author not found
     * @throws IllegalStateException if any media is not uploaded
     */
    @Transactional
    public PostResponse createPost(CreatePostRequest req) {
        // Validate author exists
        userService.getUser(req.getAuthorId());

        // Resolve media IDs to CDN URLs (validates upload completion)
        // Handle null mediaIds gracefully
        List<String> cdnUrls = mediaService.resolveCdnUrls(req.getMediaIds() != null ? req.getMediaIds() : List.of());

        // Build and save post
        Post post = new Post();
        post.setAuthorId(req.getAuthorId());
        post.setCaption(req.getCaption());
        post.setMediaType(req.getMediaType());
        post.setMediaUrls(cdnUrls);

        Post saved = postRepository.save(post);

        // Increment author's post count
        userRepository.incrementPostCount(req.getAuthorId(), 1);

        // Convert to response DTO
        PostResponse response = toResponse(saved);

        // Cache the post
        postCacheService.put(response);

        // Publish event to trigger async fanout AFTER transaction commits
        eventPublisher.publishEvent(new PostCreatedEvent(this, saved));

        log.info("Created post id={} authorId={} mediaType={} mediaCount={}",
            saved.getId(), saved.getAuthorId(), saved.getMediaType(), cdnUrls.size());

        return response;
    }

    /**
     * Get a post by ID with cache-aside pattern.
     * Checks cache first, then DB, then populates cache on miss.
     *
     * @param postId post ID
     * @return PostResponse DTO
     * @throws NoSuchElementException if post not found
     */
    public PostResponse getPost(String postId) {
        // Check cache first
        return postCacheService.get(postId)
            .orElseGet(() -> {
                // Cache miss: fetch from DB
                Post post = postRepository.findById(postId)
                    .orElseThrow(() -> new NoSuchElementException("Post not found: " + postId));

                // Convert to response
                PostResponse response = toResponse(post);

                // Populate cache
                postCacheService.put(response);

                return response;
            });
    }

    /**
     * Retrieve recent posts by multiple authors after a given timestamp.
     * Used by FeedService for fanout-on-read (celebrity posts).
     *
     * @param authorIds collection of author IDs
     * @param since minimum post creation time
     * @param limit maximum number of posts to return
     * @return list of Post entities
     */
    public List<Post> getRecentPostsByAuthors(Collection<String> authorIds, LocalDateTime since, int limit) {
        if (authorIds.isEmpty()) {
            return List.of();
        }
        return postRepository.findByAuthorIdInAndCreatedAtAfterOrderByCreatedAtDesc(
            authorIds, since, PageRequest.of(0, limit)
        );
    }

    /**
     * Convert Post entity to PostResponse DTO.
     * Resolves authorUsername from UserRepository (fallback to authorId if user deleted).
     *
     * @param post Post entity
     * @return PostResponse DTO
     */
    public PostResponse toResponse(Post post) {
        PostResponse response = new PostResponse();
        response.setId(post.getId());
        response.setAuthorId(post.getAuthorId());
        response.setCaption(post.getCaption());
        response.setMediaType(post.getMediaType().name());
        response.setMediaUrls(post.getMediaUrls());
        response.setCreatedAt(post.getCreatedAt().toString());

        // Resolve author username (graceful fallback)
        String authorUsername = userRepository.findById(post.getAuthorId())
            .map(User::getUsername)
            .orElse(post.getAuthorId());
        response.setAuthorUsername(authorUsername);

        return response;
    }
}
