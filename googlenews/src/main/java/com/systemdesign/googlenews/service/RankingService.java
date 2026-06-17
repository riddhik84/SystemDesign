package com.systemdesign.googlenews.service;

import com.systemdesign.googlenews.model.Article;
import com.systemdesign.googlenews.model.Topic;
import com.systemdesign.googlenews.model.UserInterest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RankingService {

    public List<Article> rankArticles(List<Article> articles, String userId, List<UserInterest> interests) {
        log.debug("Ranking {} articles for user {}", articles.size(), userId);

        return articles.stream()
            .map(article -> new ScoredArticle(article, calculateScore(article, interests)))
            .sorted(Comparator.comparingDouble(ScoredArticle::getScore).reversed())
            .map(ScoredArticle::getArticle)
            .collect(Collectors.toList());
    }

    private double calculateScore(Article article, List<UserInterest> interests) {
        double score = 0.0;

        score += calculateFreshnessScore(article);

        score += calculateSourceTrustScore(article);

        score += calculateTopicRelevanceScore(article, interests);

        score += calculateEngagementScore(article);

        return score;
    }

    private double calculateFreshnessScore(Article article) {
        long hoursOld = ChronoUnit.HOURS.between(article.getPublishedAt(), Instant.now());

        double freshnessScore = Math.exp(-0.1 * hoursOld);

        return freshnessScore * 10.0;
    }

    private double calculateSourceTrustScore(Article article) {
        return article.getSource().getTrustScore() * 0.5;
    }

    private double calculateTopicRelevanceScore(Article article, List<UserInterest> interests) {
        long matchingTopics = article.getTopics().stream()
            .filter(topic -> interests.stream()
                .filter(i -> i.getInterestType() == UserInterest.InterestType.TOPIC)
                .map(UserInterest::getTopic)
                .anyMatch(userTopic -> userTopic != null && userTopic.getId().equals(topic.getId())))
            .count();

        return matchingTopics * 5.0;
    }

    private double calculateEngagementScore(Article article) {
        double engagementScore = Math.log1p(article.getEngagementScore());
        return engagementScore * 2.0;
    }

    private static class ScoredArticle {
        private final Article article;
        private final double score;

        public ScoredArticle(Article article, double score) {
            this.article = article;
            this.score = score;
        }

        public Article getArticle() {
            return article;
        }

        public double getScore() {
            return score;
        }
    }
}
