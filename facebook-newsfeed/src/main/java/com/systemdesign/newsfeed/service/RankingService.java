package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.dto.PostResponse;
import com.systemdesign.newsfeed.model.Post;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-time relevance ranking layer.
 *
 * Fanout stores feeds in chronological order; ranking is applied when the feed is read.
 * Each candidate post is scored for the requesting viewer and the feed is returned in
 * descending score order.
 *
 * Scoring formula (all factors multiply):
 *   score = recencyDecay(ageHours) * engagementBoost(likes, comments) * affinity(viewer, author)
 *
 * where:
 *   - recencyDecay   = exp(-ageHours / halfLifeHours)                         (monotonic decreasing, (0,1])
 *   - engagementBoost = 1 + log1p(likeCount + 2 * commentCount)               (comments weighted 2x, >= 1)
 *   - affinity        = 1.5 if the viewer follows the author, else 1.0
 */
@Service
public class RankingService {

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private final PostService postService;
    private final FollowService followService;

    @Value("${app.ranking.half-life-hours:6}")
    private double halfLifeHours;

    public RankingService(PostService postService, FollowService followService) {
        this.postService = postService;
        this.followService = followService;
    }

    /**
     * Rank candidate posts for a viewer, descending by relevance score.
     * Each returned PostResponse carries its viewer-specific {@code score}.
     *
     * @param viewerId   the viewer the feed is being ranked for
     * @param candidates the candidate posts (chronological or arbitrary order)
     * @return ranked PostResponse list (highest score first)
     */
    public List<PostResponse> rank(String viewerId, List<Post> candidates) {
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        // Single affinity lookup for the whole batch
        Set<String> followeeIds = new HashSet<>(followService.getFolloweeIds(viewerId));

        List<PostResponse> ranked = new ArrayList<>();
        for (Post post : candidates) {
            double s = score(viewerId, post, followeeIds);
            PostResponse r = postService.toResponse(post);
            r.setScore(s);
            ranked.add(r);
        }

        // Primary: score DESC. Tie-break: createdAt DESC (ISO timestamp string compare is
        // chronologically correct) for deterministic ordering.
        ranked.sort(
            Comparator.comparingDouble((PostResponse r) -> r.getScore()).reversed()
                .thenComparing(PostResponse::getCreatedAt, Comparator.reverseOrder())
        );

        return ranked;
    }

    /**
     * Score a single post for a viewer.
     *
     * @param viewerId    the viewer (affinity is derived from {@code followeeIds})
     * @param post        the candidate post
     * @param followeeIds the set of author ids the viewer follows
     * @return the relevance score
     */
    public double score(String viewerId, Post post, Set<String> followeeIds) {
        long ageMillis = Duration.between(post.getCreatedAt(), LocalDateTime.now()).toMillis();
        double ageHours = Math.max(0, ageMillis / (double) MILLIS_PER_HOUR);

        double recencyDecay = Math.exp(-ageHours / halfLifeHours);
        double engagementBoost = 1.0 + Math.log1p(post.getLikeCount() + 2.0 * post.getCommentCount());
        double affinity = followeeIds.contains(post.getAuthorId()) ? 1.5 : 1.0;

        return recencyDecay * engagementBoost * affinity;
    }
}
