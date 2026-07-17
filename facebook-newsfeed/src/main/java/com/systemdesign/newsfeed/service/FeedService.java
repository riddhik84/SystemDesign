package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.cache.FeedCacheService;
import com.systemdesign.newsfeed.dto.FeedResponse;
import com.systemdesign.newsfeed.dto.PostResponse;
import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.repository.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Feed generation service: hybrid fanout merge + read-time ranking.
 *
 * THREE data sources are merged:
 *   1. FANOUT-ON-WRITE (precomputed): post IDs pre-pushed to the viewer's Redis feed ZSET.
 *   2. FANOUT-ON-READ (celebrity): recent posts from celebrity followees, queried on every read
 *      (celebrities are not fanned out on write because of huge follower counts).
 *   3. COLD-START FALLBACK: if the Redis feed is empty, query the DB for recent posts from all
 *      followees to bootstrap the feed.
 *
 * The merged, de-duplicated candidate set is then RANKED for relevance (RankingService) rather
 * than sorted purely chronologically, and finally paginated.
 *
 * NFR TARGET: p99 feed latency < 500ms at large scale.
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    private final FollowService followService;
    private final UserService userService;
    private final PostService postService;
    private final FeedCacheService feedCacheService;
    private final RankingService rankingService;
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
                       RankingService rankingService,
                       PostRepository postRepository) {
        this.followService = followService;
        this.userService = userService;
        this.postService = postService;
        this.feedCacheService = feedCacheService;
        this.rankingService = rankingService;
        this.postRepository = postRepository;
    }

    /**
     * Build the feed for a user (hybrid merge -> ranking -> pagination).
     *
     * @param userId   the user requesting the feed
     * @param page     zero-based page number
     * @param pageSize number of posts per page
     * @return the FeedResponse with ranked posts, pagination metadata, and a strategy note
     */
    public FeedResponse getFeed(String userId, int page, int pageSize) {
        long startMs = System.currentTimeMillis();

        // Validate user exists (throws NoSuchElementException -> 404 if missing)
        userService.getUser(userId);

        // 1. Whom does this user follow?
        List<String> followeeIds = followService.getFolloweeIds(userId);
        if (followeeIds.isEmpty()) {
            log.debug("User id={} follows nobody; returning empty feed", userId);
            return buildEmptyFeed(userId, page, pageSize);
        }

        // 2. FANOUT-ON-WRITE source: precomputed post IDs from Redis (paginate after merge)
        List<String> precomputedPostIds = feedCacheService.getFeedPostIds(userId, 0, maxCachedFeed);

        // 3. FANOUT-ON-READ source: recent posts from celebrity followees
        List<String> celebrityFolloweeIds = followeeIds.stream()
            .filter(followService::isCelebrity)
            .collect(Collectors.toList());

        LocalDateTime celebrityCutoff = LocalDateTime.now().minusHours(celebrityLookbackHours);
        List<Post> celebrityPosts = celebrityFolloweeIds.isEmpty()
            ? List.of()
            : postService.getRecentPostsByAuthors(celebrityFolloweeIds, celebrityCutoff, pageSize * 3 + 1);

        // 4. COLD-START FALLBACK: if the precomputed feed is empty, hydrate from the DB
        boolean coldStart = precomputedPostIds.isEmpty();
        List<Post> fallbackPosts = List.of();
        if (coldStart) {
            log.debug("Cold start for user id={}: fetching from DB", userId);
            fallbackPosts = postRepository.findByAuthorIdInOrderByCreatedAtDesc(
                followeeIds, PageRequest.of(0, pageSize * 3));
        }

        // 5. MERGE + DEDUP (LinkedHashMap keeps first-seen order; ranking reorders afterwards)
        Map<String, Post> postMap = new LinkedHashMap<>();
        for (Post post : fallbackPosts) {
            postMap.put(post.getId(), post);
        }
        for (Post post : celebrityPosts) {
            postMap.put(post.getId(), post);
        }
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

        // 6. RANK: read-time relevance layer (replaces plain chronological sort)
        List<PostResponse> ranked = rankingService.rank(userId, new ArrayList<>(postMap.values()));

        // 7. Paginate the ranked list
        int startIdx = page * pageSize;
        int endIdx = Math.min(startIdx + pageSize, ranked.size());
        List<PostResponse> pagePosts = (startIdx < ranked.size())
            ? ranked.subList(startIdx, endIdx)
            : List.of();

        // 8. Build the response
        FeedResponse response = new FeedResponse();
        response.setUserId(userId);
        response.setPosts(pagePosts);
        response.setPage(page);
        response.setPageSize(pageSize);
        response.setHasMore(ranked.size() > startIdx + pageSize);
        response.setServedAtEpochMs(System.currentTimeMillis());

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
            userId, page, pagePosts.size(), elapsedMs, strategyNote);

        return response;
    }

    /**
     * Assemble a descriptive strategy note explaining which sources contributed to the feed,
     * appending the ranking-layer marker when the feed is non-empty.
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

        if (notes.isEmpty()) {
            return "EMPTY_FEED";
        }
        return String.join(" + ", notes) + " + RANKED (relevance: recency×engagement×affinity)";
    }

    /**
     * Build an empty feed response (when the user follows nobody).
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
