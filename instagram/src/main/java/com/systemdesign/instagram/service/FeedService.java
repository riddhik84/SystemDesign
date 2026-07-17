package com.systemdesign.instagram.service;

import com.systemdesign.instagram.cache.FeedCacheService;
import com.systemdesign.instagram.dto.FeedResponse;
import com.systemdesign.instagram.dto.PostResponse;
import com.systemdesign.instagram.model.Post;
import com.systemdesign.instagram.repository.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Feed generation service with hybrid fanout strategy.
 *
 * ARCHITECTURE (hybrid fanout-on-write + fanout-on-read):
 *
 * THREE DATA SOURCES are merged:
 *   1. FANOUT-ON-WRITE (precomputed): Posts from normal users are pre-pushed to followers' Redis feeds
 *      when created. Read from Redis ZSET: insta:feed:{userId}
 *   2. FANOUT-ON-READ (celebrity): Posts from celebrity users are NOT fanned out (too expensive).
 *      Instead, we query the DB for recent celebrity posts on every feed read.
 *   3. COLD-START FALLBACK: If Redis feed is empty (new user or cache miss), we query the DB
 *      for all recent posts from followed users to bootstrap the feed.
 *
 * MERGE LOGIC:
 *   - Fetch precomputed post IDs from Redis (offset/limit for pagination)
 *   - Fetch recent celebrity posts from DB (last N hours, configurable)
 *   - If precomputed is empty (cold start), fetch all recent posts from DB
 *   - Deduplicate by post ID, sort by createdAt desc, apply page/pageSize window
 *   - Hydrate post IDs to PostResponse DTOs (cache-aside via PostService)
 *
 * WHY HYBRID?
 *   - Normal users: low follower count -> fanout-on-write is cheap
 *   - Celebrities: huge follower count -> fanout-on-write would be too expensive (millions of Redis writes)
 *   - This balances write amplification (fanout cost) with read latency (query cost)
 *
 * NFR TARGET: p99 feed latency < 500ms @ 500M DAU, 100M posts/day
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    private final FollowService followService;
    private final UserService userService;
    private final PostService postService;
    private final FeedCacheService feedCacheService;
    private final PostRepository postRepository;

    @Value("${app.feed.page-size:20}")
    private int defaultPageSize;

    @Value("${app.feed.celebrity-lookback-hours:24}")
    private int celebrityLookbackHours;

    @Value("${app.feed.max-cached-feed:1000}")
    private int maxCachedFeed;

    public FeedService(FollowService followService,
                       UserService userService,
                       PostService postService,
                       FeedCacheService feedCacheService,
                       PostRepository postRepository) {
        this.followService = followService;
        this.userService = userService;
        this.postService = postService;
        this.feedCacheService = feedCacheService;
        this.postRepository = postRepository;
    }

    /**
     * Get the feed for a user with hybrid fanout strategy.
     *
     * @param userId the user requesting the feed
     * @param page zero-based page number
     * @param pageSize number of posts per page
     * @return FeedResponse with posts, pagination metadata, and strategy note
     */
    public FeedResponse getFeed(String userId, int page, int pageSize) {
        long startMs = System.currentTimeMillis();

        // Validate user exists (throws NoSuchElementException -> 404 if not found)
        userService.getUser(userId);

        // 1. Get the list of users this user follows
        List<String> followeeIds = followService.getFolloweeIds(userId);
        if (followeeIds.isEmpty()) {
            log.debug("User id={} follows nobody; returning empty feed", userId);
            return buildEmptyFeed(userId, page, pageSize);
        }

        // 2. Fetch precomputed posts from Redis (fanout-on-write)
        // Fetch all cached posts without pagination - we'll paginate after merging
        List<String> precomputedPostIds = feedCacheService.getFeedPostIds(
            userId, 0, maxCachedFeed
        );

        // 3. Identify celebrity followees and fetch their recent posts (fanout-on-read)
        List<String> celebrityFolloweeIds = followeeIds.stream()
            .filter(followService::isCelebrity)
            .collect(Collectors.toList());

        LocalDateTime celebrityCutoff = LocalDateTime.now().minusHours(celebrityLookbackHours);
        List<Post> celebrityPosts = celebrityFolloweeIds.isEmpty()
            ? List.of()
            : postService.getRecentPostsByAuthors(celebrityFolloweeIds, celebrityCutoff, pageSize * 3 + 1);

        // 4. COLD-START FALLBACK: if precomputed is empty, fetch from DB
        List<Post> fallbackPosts = List.of();
        boolean coldStart = precomputedPostIds.isEmpty();
        if (coldStart) {
            log.debug("Cold start for user id={}: fetching from DB", userId);
            fallbackPosts = postRepository.findByAuthorIdInOrderByCreatedAtDesc(
                followeeIds, PageRequest.of(0, pageSize * 3)
            );
        }

        // 5. MERGE: combine precomputed, celebrity, and fallback posts
        Map<String, Post> postMap = new LinkedHashMap<>();

        // Add fallback posts (if cold start)
        for (Post post : fallbackPosts) {
            postMap.put(post.getId(), post);
        }

        // Add celebrity posts (fanout-on-read)
        for (Post post : celebrityPosts) {
            postMap.put(post.getId(), post);
        }

        // Hydrate precomputed post IDs (fanout-on-write)
        for (String postId : precomputedPostIds) {
            if (!postMap.containsKey(postId)) {
                try {
                    Post post = postRepository.findById(postId).orElse(null);
                    if (post != null) {
                        postMap.put(postId, post);
                    }
                } catch (Exception e) {
                    log.warn("Failed to hydrate post id={}: {}", postId, e.getMessage());
                }
            }
        }

        // 6. Sort by createdAt desc (most recent first)
        List<Post> allPosts = postMap.values().stream()
            .sorted(Comparator.comparing(Post::getCreatedAt).reversed())
            .collect(Collectors.toList());

        // 7. Apply pagination window
        int startIdx = page * pageSize;
        int endIdx = Math.min(startIdx + pageSize, allPosts.size());
        List<Post> pagePosts = (startIdx < allPosts.size())
            ? allPosts.subList(startIdx, Math.min(startIdx + pageSize, allPosts.size()))
            : List.of();

        // 8. Hydrate Post entities to PostResponse DTOs
        List<PostResponse> postResponses = pagePosts.stream()
            .map(postService::toResponse)
            .collect(Collectors.toList());

        // 9. Build response
        FeedResponse response = new FeedResponse();
        response.setUserId(userId);
        response.setPosts(postResponses);
        response.setPage(page);
        response.setPageSize(pageSize);
        response.setHasMore(allPosts.size() > startIdx + pageSize);
        response.setServedAtEpochMs(System.currentTimeMillis());

        // 10. Build strategy note
        String strategyNote = buildStrategyNote(
            precomputedPostIds.size(),
            celebrityPosts.size(),
            fallbackPosts.size(),
            coldStart,
            celebrityFolloweeIds.size()
        );
        response.setFeedStrategyNote(strategyNote);

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("Feed served userId={} page={} posts={} elapsed={}ms strategy={}",
            userId, page, postResponses.size(), elapsedMs, strategyNote);

        return response;
    }

    /**
     * Build a descriptive strategy note explaining which data sources contributed to the feed.
     */
    private String buildStrategyNote(int precomputedCount, int celebrityCount, int fallbackCount,
                                     boolean coldStart, int celebrityFolloweeCount) {
        List<String> notes = new ArrayList<>();

        if (coldStart) {
            notes.add("COLD_START (precomputed empty, used DB fallback: " + fallbackCount + " posts)");
        } else if (precomputedCount > 0) {
            notes.add("FANOUT_ON_WRITE (precomputed: " + precomputedCount + " post IDs from Redis)");
        }

        if (celebrityCount > 0) {
            notes.add("FANOUT_ON_READ (celebrity: " + celebrityCount + " posts from " +
                celebrityFolloweeCount + " celebrities, last " + celebrityLookbackHours + "h)");
        }

        return notes.isEmpty() ? "EMPTY_FEED" : String.join(" + ", notes);
    }

    /**
     * Build an empty feed response (when user follows nobody).
     */
    private FeedResponse buildEmptyFeed(String userId, int page, int pageSize) {
        FeedResponse response = new FeedResponse();
        response.setUserId(userId);
        response.setPosts(List.of());
        response.setPage(page);
        response.setPageSize(pageSize);
        response.setHasMore(false);
        response.setServedAtEpochMs(System.currentTimeMillis());
        response.setFeedStrategyNote("EMPTY_FEED (user follows nobody)");
        return response;
    }
}
