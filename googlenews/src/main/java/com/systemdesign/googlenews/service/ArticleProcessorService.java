package com.systemdesign.googlenews.service;

import com.rometools.rome.feed.synd.SyndEntry;
import com.systemdesign.googlenews.model.Article;
import com.systemdesign.googlenews.model.Source;
import com.systemdesign.googlenews.model.Topic;
import com.systemdesign.googlenews.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleProcessorService {

    private final ArticleRepository articleRepository;
    private final TopicClassifierService topicClassifierService;

    @Transactional
    @CacheEvict(value = "feed", allEntries = true)
    public void processEntry(SyndEntry entry, Source source) {
        String contentHash = hashContent(entry.getTitle(), getContent(entry));

        Instant lookback = Instant.now().minus(7, ChronoUnit.DAYS);
        Optional<Article> existing = articleRepository
            .findByContentHashAndPublishedAtAfter(contentHash, lookback);

        if (existing.isPresent()) {
            log.debug("Duplicate article detected: {}", entry.getTitle());
            return;
        }

        String content = getContent(entry);
        Set<Topic> topics = topicClassifierService.classifyArticle(entry.getTitle(), content);

        Article article = Article.builder()
            .id(UUID.randomUUID().toString())
            .title(truncate(entry.getTitle(), 500))
            .summary(truncate(entry.getDescription() != null ? entry.getDescription().getValue() : "", 1000))
            .content(content)
            .url(entry.getLink())
            .imageUrl(extractImageUrl(entry))
            .source(source)
            .topics(topics)
            .publishedAt(getPublishedDate(entry))
            .contentHash(contentHash)
            .viewCount(0)
            .engagementScore(0)
            .status(Article.ArticleStatus.ACTIVE)
            .build();

        articleRepository.save(article);
        log.debug("Saved new article: {}", article.getTitle());
    }

    private String hashContent(String title, String content) {
        try {
            String normalized = normalize(title + " " + content);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String normalize(String text) {
        return text.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private String getContent(SyndEntry entry) {
        if (entry.getContents() != null && !entry.getContents().isEmpty()) {
            return entry.getContents().get(0).getValue();
        } else if (entry.getDescription() != null) {
            return entry.getDescription().getValue();
        }
        return "";
    }

    private Instant getPublishedDate(SyndEntry entry) {
        Date published = entry.getPublishedDate();
        if (published == null) {
            published = entry.getUpdatedDate();
        }
        if (published == null) {
            return Instant.now();
        }
        return published.toInstant();
    }

    private String extractImageUrl(SyndEntry entry) {
        if (entry.getEnclosures() != null && !entry.getEnclosures().isEmpty()) {
            return entry.getEnclosures().get(0).getUrl();
        }
        return null;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
