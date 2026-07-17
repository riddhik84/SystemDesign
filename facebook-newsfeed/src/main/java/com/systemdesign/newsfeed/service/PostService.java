package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.cache.PostCacheService;
import com.systemdesign.newsfeed.dto.CreatePostRequest;
import com.systemdesign.newsfeed.dto.PostResponse;
import com.systemdesign.newsfeed.event.PostCreatedEvent;
import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.repository.PostRepository;
import com.systemdesign.newsfeed.repository.UserRepository;
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
 * - Create posts (plain text content + optional single media URL)
 * - Retrieve posts with a cache-aside pattern
 * - Bump engagement counters (likes / comments) that drive the ranking signals
 * - Trigger async fanout to followers' feeds (via PostCreatedEvent)
 * - Query recent posts by authors (for feed fanout-on-read merge)
 *
 * Flow on createPost:
 *   1. Validate author exists
 *   2. Save the Post entity (content + optional mediaUrl)
 *   3. Increment the author's post count
 *   4. Cache the post response
 *   5. Publish PostCreatedEvent (async fanout runs AFTER commit)
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final PostCacheService postCacheService;
    private final ApplicationEventPublisher eventPublisher;

    public PostService(PostRepository postRepository,
                       UserRepository userRepository,
                       UserService userService,
                       PostCacheService postCacheService,
                       ApplicationEventPublisher eventPublisher) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.postCacheService = postCacheService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Create a new post.
     *
     * @param req post creation request
     * @return PostResponse DTO
     * @throws NoSuchElementException if the author does not exist
     */
    @Transactional
    public PostResponse createPost(CreatePostRequest req) {
        // Validate author exists (throws NoSuchElementException -> 404 if missing)
        userService.getUser(req.getAuthorId());

        // Build and save the post (mediaUrl is optional / nullable; counters default to 0)
        Post post = new Post();
        post.setAuthorId(req.getAuthorId());
        post.setContent(req.getContent());
        post.setMediaUrl(req.getMediaUrl());

        Post saved = postRepository.save(post);

        // Increment the author's post count
        userRepository.incrementPostCount(req.getAuthorId(), 1);

        // Convert to response DTO and cache it
        PostResponse response = toResponse(saved);
        postCacheService.put(response);

        // Publish event to trigger async fanout AFTER the transaction commits
        eventPublisher.publishEvent(new PostCreatedEvent(this, saved));

        log.info("Created post id={} authorId={} hasMedia={}",
            saved.getId(), saved.getAuthorId(), saved.getMediaUrl() != null);

        return response;
    }

    /**
     * Get a post by id using a cache-aside pattern.
     * Checks the cache first, then the DB, then populates the cache on a miss.
     *
     * @param postId post id
     * @return PostResponse DTO
     * @throws NoSuchElementException if the post does not exist
     */
    public PostResponse getPost(String postId) {
        return postCacheService.get(postId)
            .orElseGet(() -> {
                Post post = postRepository.findById(postId)
                    .orElseThrow(() -> new NoSuchElementException("Post not found: " + postId));
                PostResponse response = toResponse(post);
                postCacheService.put(response);
                return response;
            });
    }

    /**
     * Register a like on a post: bump the like counter, evict the stale cache entry,
     * and return the freshly re-read (and re-cached) response.
     *
     * @param postId post id
     * @return the updated PostResponse
     * @throws NoSuchElementException if the post does not exist
     */
    @Transactional
    public PostResponse likePost(String postId) {
        // Validate existence WITHOUT loading the entity into the persistence context.
        // A bulk @Modifying UPDATE does not refresh managed entities, so a prior findById
        // would leave a stale copy in the L1 cache and make the re-read below return the old
        // count. existsById issues a lightweight existence check and keeps the L1 cache clean,
        // so the getPost re-read observes the just-written value.
        if (!postRepository.existsById(postId)) {
            throw new NoSuchElementException("Post not found: " + postId);
        }

        postRepository.incrementLikeCount(postId, 1);
        postCacheService.evict(postId);

        log.info("Liked post id={}", postId);
        return getPost(postId);
    }

    /**
     * Register a comment on a post: bump the comment counter, evict the stale cache entry,
     * and return the freshly re-read (and re-cached) response.
     *
     * @param postId post id
     * @return the updated PostResponse
     * @throws NoSuchElementException if the post does not exist
     */
    @Transactional
    public PostResponse commentPost(String postId) {
        // See likePost for why existence is validated with existsById rather than findById.
        if (!postRepository.existsById(postId)) {
            throw new NoSuchElementException("Post not found: " + postId);
        }

        postRepository.incrementCommentCount(postId, 1);
        postCacheService.evict(postId);

        log.info("Commented post id={}", postId);
        return getPost(postId);
    }

    /**
     * Retrieve recent posts by multiple authors after a given timestamp.
     * Used by FeedService for fanout-on-read (celebrity posts).
     *
     * @param authorIds collection of author ids
     * @param since minimum post creation time
     * @param limit maximum number of posts to return
     * @return list of Post entities (reverse-chronological)
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
     * Convert a Post entity to a PostResponse DTO.
     * Resolves authorUsername from UserRepository (falls back to authorId if the user is gone).
     * Leaves {@code score} null; ranking scores are attached later by RankingService for feed reads.
     *
     * @param post Post entity
     * @return PostResponse DTO
     */
    public PostResponse toResponse(Post post) {
        PostResponse response = new PostResponse();
        response.setId(post.getId());
        response.setAuthorId(post.getAuthorId());
        response.setContent(post.getContent());
        response.setMediaUrl(post.getMediaUrl());
        response.setLikeCount(post.getLikeCount());
        response.setCommentCount(post.getCommentCount());
        response.setCreatedAt(post.getCreatedAt().toString());

        // Resolve author username (graceful fallback to id if the user was deleted)
        String authorUsername = userRepository.findById(post.getAuthorId())
            .map(User::getUsername)
            .orElse(post.getAuthorId());
        response.setAuthorUsername(authorUsername);

        // score intentionally left null for non-feed reads
        return response;
    }
}
