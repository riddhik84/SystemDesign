package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.cache.FeedCacheService;
import com.systemdesign.newsfeed.event.PostCreatedEvent;
import com.systemdesign.newsfeed.model.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneOffset;
import java.util.List;

/**
 * Fanout-on-write service for pushing posts into follower feeds.
 *
 * Strategy (hybrid fanout):
 * - Normal users: fan the post out to every follower's Redis feed cache (fanout-on-write).
 * - Celebrity users: skip fanout; their posts are fetched directly by FeedService (fanout-on-read).
 *
 * This balances write amplification against read latency:
 * - Normal users have low follower counts, so fanout is cheap.
 * - Celebrities have huge follower counts, so fanning out would be prohibitively expensive;
 *   instead, FeedService queries their recent posts on every feed read.
 *
 * The @Async annotation runs fanout on a background thread pool (fanoutExecutor) so that
 * post creation does not block on Redis writes. The @TransactionalEventListener(AFTER_COMMIT)
 * ensures the async thread never reads uncommitted post data.
 */
@Service
public class FanoutService {

    private static final Logger log = LoggerFactory.getLogger(FanoutService.class);

    private final FollowService followService;
    private final FeedCacheService feedCacheService;

    public FanoutService(FollowService followService,
                         FeedCacheService feedCacheService) {
        this.followService = followService;
        this.feedCacheService = feedCacheService;
    }

    /**
     * Event listener that triggers fanout AFTER the post transaction commits.
     * Runs asynchronously on the fanoutExecutor thread pool.
     *
     * @param event the post created event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("fanoutExecutor")
    public void onPostCreated(PostCreatedEvent event) {
        fanoutSync(event.getPost());
    }

    /**
     * Synchronous fanout core (deterministic for tests).
     * Skips celebrities (handled by fanout-on-read); otherwise pushes the post
     * into every follower's Redis feed.
     *
     * @param post the post to fan out
     */
    public void fanoutSync(Post post) {
        // Celebrities are handled by fanout-on-read: skip the write fanout entirely.
        if (followService.isCelebrity(post.getAuthorId())) {
            log.info("Skipping fanout for celebrity post id={} authorId={} (fanout-on-read)",
                post.getId(), post.getAuthorId());
            return;
        }

        // Gather the author's followers
        List<String> followerIds = followService.getFollowerIds(post.getAuthorId());
        if (followerIds.isEmpty()) {
            log.debug("No followers to fanout for post id={} authorId={}",
                post.getId(), post.getAuthorId());
            return;
        }

        // Score = createdAt epoch millis (ZSET score used for reverse-chronological ordering)
        long scoreMs = post.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();

        // Push the post into every follower's feed
        feedCacheService.addToManyFeeds(followerIds, post.getId(), scoreMs);

        log.info("Fanned out post id={} authorId={} to {} followers",
            post.getId(), post.getAuthorId(), followerIds.size());
    }
}
