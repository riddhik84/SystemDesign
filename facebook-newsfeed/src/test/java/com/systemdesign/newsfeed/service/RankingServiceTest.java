package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.dto.PostResponse;
import com.systemdesign.newsfeed.model.Post;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * RankingService tests: plain Mockito unit tests (no Spring context).
 *
 * Verifies the read-time relevance formula:
 *   score = recencyDecay(ageHours) * engagementBoost(likes, comments) * affinity(viewer, author)
 * and the DESC ordering produced by {@link RankingService#rank(String, List)}.
 */
@ExtendWith(MockitoExtension.class)
class RankingServiceTest {

    @Mock
    private PostService postService;

    @Mock
    private FollowService followService;

    private RankingService ranking;

    @BeforeEach
    void setUp() {
        ranking = new RankingService(postService, followService);
        ReflectionTestUtils.setField(ranking, "halfLifeHours", 6.0);
    }

    @Test
    void higherEngagementRanksHigher() {
        String viewer = "viewer";
        LocalDateTime now = LocalDateTime.now();
        Post low = post("low", "authorX", 1, 0, now);
        Post high = post("high", "authorX", 50, 10, now);
        Set<String> followees = Set.of("authorX");

        // Equal age & affinity -> more engagement scores strictly higher.
        assertThat(ranking.score(viewer, high, followees))
            .isGreaterThan(ranking.score(viewer, low, followees));

        when(followService.getFolloweeIds(viewer)).thenReturn(List.of("authorX"));
        when(postService.toResponse(any(Post.class))).thenAnswer(this::echo);

        List<PostResponse> ranked = ranking.rank(viewer, List.of(low, high));
        assertThat(ranked.get(0).getId()).isEqualTo("high");
    }

    @Test
    void moreRecentRanksHigher() {
        String viewer = "viewer";
        LocalDateTime now = LocalDateTime.now();
        Post older = post("older", "authorX", 5, 1, now.minusHours(3));
        Post newer = post("newer", "authorX", 5, 1, now);
        Set<String> followees = Set.of("authorX");

        // Equal engagement & affinity -> more recent (smaller age) scores strictly higher.
        assertThat(ranking.score(viewer, newer, followees))
            .isGreaterThan(ranking.score(viewer, older, followees));

        when(followService.getFolloweeIds(viewer)).thenReturn(List.of("authorX"));
        when(postService.toResponse(any(Post.class))).thenAnswer(this::echo);

        List<PostResponse> ranked = ranking.rank(viewer, List.of(older, newer));
        assertThat(ranked.get(0).getId()).isEqualTo("newer");
    }

    @Test
    void followedAuthorRanksHigherThanNonFollowed() {
        String viewer = "viewer";
        LocalDateTime now = LocalDateTime.now();
        Post followed = post("followed", "followedAuthor", 5, 1, now);
        Post notFollowed = post("notFollowed", "strangerAuthor", 5, 1, now);
        Set<String> followees = Set.of("followedAuthor");

        // Equal age & engagement -> affinity (1.5 vs 1.0) makes the followed author score higher.
        assertThat(ranking.score(viewer, followed, followees))
            .isGreaterThan(ranking.score(viewer, notFollowed, followees));

        when(followService.getFolloweeIds(viewer)).thenReturn(List.of("followedAuthor"));
        when(postService.toResponse(any(Post.class))).thenAnswer(this::echo);

        List<PostResponse> ranked = ranking.rank(viewer, List.of(notFollowed, followed));
        assertThat(ranked.get(0).getId()).isEqualTo("followed");
    }

    @Test
    void rankReturnsEmptyForEmptyCandidates() {
        List<PostResponse> ranked = ranking.rank("viewer", List.of());

        assertThat(ranked).isEmpty();
    }

    @Test
    void rankSetsScoreOnEachResponse() {
        String viewer = "viewer";
        LocalDateTime now = LocalDateTime.now();
        Post p1 = post("p1", "authorX", 10, 2, now);
        Post p2 = post("p2", "authorX", 1, 0, now.minusHours(1));

        when(followService.getFolloweeIds(viewer)).thenReturn(List.of("authorX"));
        when(postService.toResponse(any(Post.class))).thenAnswer(this::echo);

        List<PostResponse> ranked = ranking.rank(viewer, List.of(p2, p1));

        assertThat(ranked).hasSize(2);
        assertThat(ranked).allSatisfy(r -> assertThat(r.getScore()).isNotNull());
        // DESC ordered by score.
        for (int i = 0; i + 1 < ranked.size(); i++) {
            assertThat(ranked.get(i).getScore())
                .isGreaterThanOrEqualTo(ranked.get(i + 1).getScore());
        }
    }

    /** Echo a Post argument into a matching PostResponse (mirrors PostService.toResponse fields). */
    private PostResponse echo(InvocationOnMock inv) {
        Post p = inv.getArgument(0);
        PostResponse r = new PostResponse();
        r.setId(p.getId());
        r.setAuthorId(p.getAuthorId());
        r.setLikeCount(p.getLikeCount());
        r.setCommentCount(p.getCommentCount());
        r.setCreatedAt(p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        return r;
    }

    private Post post(String id, String authorId, int likes, int comments, LocalDateTime createdAt) {
        Post p = new Post();
        p.setId(id);
        p.setAuthorId(authorId);
        p.setLikeCount(likes);
        p.setCommentCount(comments);
        p.setCreatedAt(createdAt);
        return p;
    }
}
