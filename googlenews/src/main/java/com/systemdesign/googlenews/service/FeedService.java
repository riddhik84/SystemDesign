package com.systemdesign.googlenews.service;

import com.systemdesign.googlenews.dto.ArticleDTO;
import com.systemdesign.googlenews.dto.FeedResponse;
import com.systemdesign.googlenews.dto.SourceDTO;
import com.systemdesign.googlenews.model.Article;
import com.systemdesign.googlenews.model.Topic;
import com.systemdesign.googlenews.model.UserInterest;
import com.systemdesign.googlenews.repository.ArticleRepository;
import com.systemdesign.googlenews.repository.UserInterestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedService {

    private final ArticleRepository articleRepository;
    private final UserInterestRepository userInterestRepository;
    private final RankingService rankingService;

    @Transactional(readOnly = true)
    @Cacheable(value = "feed", key = "#userId + '_' + #pageable.pageNumber")
    public FeedResponse getPersonalizedFeed(String userId, Pageable pageable) {
        log.debug("Generating personalized feed for user: {}", userId);

        List<UserInterest> interests = userInterestRepository.findByUserId(userId);

        if (interests.isEmpty()) {
            log.debug("User {} has no interests, returning trending feed", userId);
            return getTrendingFeed(pageable);
        }

        Set<Topic> topics = extractTopics(interests);
        Set<String> sourceIds = extractSourceIds(interests);

        Instant lookbackTime = Instant.now().minus(24, ChronoUnit.HOURS);

        Page<Article> candidates = articleRepository.findCandidateArticles(
            topics,
            sourceIds,
            lookbackTime,
            PageRequest.of(0, 500)
        );

        List<Article> rankedArticles = rankingService.rankArticles(
            candidates.getContent(),
            userId,
            interests
        );

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), rankedArticles.size());
        List<Article> pageArticles = rankedArticles.subList(start, end);

        List<ArticleDTO> articleDTOs = pageArticles.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());

        return FeedResponse.builder()
            .articles(articleDTOs)
            .page(pageable.getPageNumber())
            .totalPages((rankedArticles.size() + pageable.getPageSize() - 1) / pageable.getPageSize())
            .totalElements(rankedArticles.size())
            .hasMore(end < rankedArticles.size())
            .build();
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "trending", key = "'global_' + #pageable.pageNumber")
    public FeedResponse getTrendingFeed(Pageable pageable) {
        log.debug("Generating trending feed");

        Instant lookbackTime = Instant.now().minus(6, ChronoUnit.HOURS);

        Page<Article> trending = articleRepository.findTrendingArticles(
            lookbackTime,
            pageable
        );

        List<ArticleDTO> articleDTOs = trending.getContent().stream()
            .map(this::toDTO)
            .collect(Collectors.toList());

        return FeedResponse.builder()
            .articles(articleDTOs)
            .page(trending.getNumber())
            .totalPages(trending.getTotalPages())
            .totalElements(trending.getTotalElements())
            .hasMore(trending.hasNext())
            .build();
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "article", key = "#articleId")
    public Optional<ArticleDTO> getArticle(String articleId) {
        return articleRepository.findById(articleId)
            .filter(article -> article.getStatus() == Article.ArticleStatus.ACTIVE)
            .map(article -> {
                article.setViewCount(article.getViewCount() + 1);
                article.setEngagementScore(article.getEngagementScore() + 1);
                return toDTO(article);
            });
    }

    private Set<Topic> extractTopics(List<UserInterest> interests) {
        return interests.stream()
            .filter(i -> i.getInterestType() == UserInterest.InterestType.TOPIC)
            .map(UserInterest::getTopic)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    private Set<String> extractSourceIds(List<UserInterest> interests) {
        return interests.stream()
            .filter(i -> i.getInterestType() == UserInterest.InterestType.SOURCE)
            .map(UserInterest::getSource)
            .filter(Objects::nonNull)
            .map(com.systemdesign.googlenews.model.Source::getId)
            .collect(Collectors.toSet());
    }

    private ArticleDTO toDTO(Article article) {
        return ArticleDTO.builder()
            .id(article.getId())
            .title(article.getTitle())
            .summary(article.getSummary())
            .content(article.getContent())
            .url(article.getUrl())
            .imageUrl(article.getImageUrl())
            .source(SourceDTO.builder()
                .id(article.getSource().getId())
                .name(article.getSource().getName())
                .domain(article.getSource().getDomain())
                .build())
            .topics(article.getTopics().stream()
                .map(Topic::getName)
                .collect(Collectors.toSet()))
            .publishedAt(article.getPublishedAt())
            .engagementScore(article.getEngagementScore())
            .build();
    }
}
