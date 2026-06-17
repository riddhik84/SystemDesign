package com.systemdesign.googlenews.repository;

import com.systemdesign.googlenews.model.Article;
import com.systemdesign.googlenews.model.Topic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface ArticleRepository extends JpaRepository<Article, String> {

    Optional<Article> findByContentHashAndPublishedAtAfter(String contentHash, Instant after);

    @Query("SELECT a FROM Article a WHERE " +
           "(a.topics IN :topics OR a.source.id IN :sourceIds) AND " +
           "a.publishedAt > :after AND " +
           "a.status = 'ACTIVE' " +
           "ORDER BY a.publishedAt DESC")
    Page<Article> findCandidateArticles(
        @Param("topics") Set<Topic> topics,
        @Param("sourceIds") Set<String> sourceIds,
        @Param("after") Instant after,
        Pageable pageable
    );

    List<Article> findByPublishedAtAfterAndStatus(
        Instant after,
        Article.ArticleStatus status
    );

    int countByContentHash(String contentHash);

    Page<Article> findByTopicsInAndPublishedAtAfterAndStatus(
        Set<Topic> topics,
        Instant after,
        Article.ArticleStatus status,
        Pageable pageable
    );

    @Query("SELECT a FROM Article a WHERE " +
           "a.publishedAt > :after AND " +
           "a.status = 'ACTIVE' " +
           "ORDER BY a.engagementScore DESC")
    Page<Article> findTrendingArticles(
        @Param("after") Instant after,
        Pageable pageable
    );
}
