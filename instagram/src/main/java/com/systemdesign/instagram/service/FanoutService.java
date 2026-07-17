package com.systemdesign.instagram.service;

import com.systemdesign.instagram.cache.FeedCacheService;
import com.systemdesign.instagram.event.PostCreatedEvent;
import com.systemdesign.instagram.model.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneOffset;
import java.util.List;

/**
 * Fanout-on-write service for pushing posts to follower feeds.
 *
 * Strategy (hybrid fanout):
 * - Normal users: fanout post to all followers' Redis feed caches (fanout-on-write)
 * - Celebrity users: skip fanout; their posts are fetched directly by FeedService (fanout-on-read)
 *
 * This hybrid approach balances write amplification with read latency:
 * - Normal users have low follower counts, so fanout is cheap
 * - Celebrities have huge follower counts, so fanout would be prohibitively expensive;
 *   instead, FeedService queries their recent posts on every feed read
 *
 * The @Async annotation runs fanout in a background thread pool (fanoutExecutor)
 * so that post creation doesn't block on Redis writes.
 */
@Service
public class FanoutService {

    private static final Logger log = LoggerFactory.getLogger(FanoutService.class);

    private final FollowService followService;
    private final UserService userService;
    private final FeedCacheService feedCacheService;

    public FanoutService(FollowService followService,
                         UserService userService,
                         FeedCacheService feedCacheService) {
        this.followService = followService;
        this.userService = userService;
        this.feedCacheService = feedCacheService;
    }

    /**
     * Event listener that triggers fanout AFTER the post transaction commits.
     * This prevents race conditions where the async fanout thread reads uncommitted data.
     * The @Async annotation ensures fanout happens in a background thread.
     *
     * @param event the post created event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("fanoutExecutor")
    public void onPostCreated(PostCreatedEvent event) {
        fanoutSync(event.getPost());
    }

    /**
     * Asynchronously fan out a post to all followers' feeds.
     * Skips fanout for celebrity users (fanout-on-read will handle them).
     * Deprecated: Use event listener instead to avoid read-before-commit races.
     *
     * @param post the post to fan out
     * @deprecated Use PostCreatedEvent publishing instead
     */
    @Deprecated
    @Async("fanoutExecutor")
    public void fanout(Post post) {
        fanoutSync(post);
    }

    /**
     * Synchronous fanout implementation (for deterministic testing).
     * Checks if author is a celebrity; if so, skips fanout.
     * Otherwise, pushes the post to all followers' Redis feeds.
     *
     * @param post the post to fan out
     */
    public void fanoutSync(Post post) {
        // Check if author is celebrity
        boolean isCelebrity = followService.isCelebrity(post.getAuthorId());
        if (isCelebrity) {
            log.info("Skipping fanout for celebrity post id={} authorId={} (fanout-on-read)",
                post.getId(), post.getAuthorId());
            return;
        }

        // Get all followers
        List<String> followerIds = followService.getFollowerIds(post.getAuthorId());
        if (followerIds.isEmpty()) {
            log.debug("No followers to fanout for post id={} authorId={}",
                post.getId(), post.getAuthorId());
            return;
        }

        // Convert createdAt to epoch millis for ZSET score
        long scoreMs = post.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();

        // Push post to all followers' feeds
        feedCacheService.addToManyFeeds(followerIds, post.getId(), scoreMs);

        log.info("Fanned out post id={} authorId={} to {} followers",
            post.getId(), post.getAuthorId(), followerIds.size());
    }
}
