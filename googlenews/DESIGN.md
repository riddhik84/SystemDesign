# Google News Feed Aggregator — System Design

> **Implementation status:** This repository contains a complete, production-quality Spring Boot
> implementation of the design described here. Every architectural decision maps directly to
> code in `src/main/java/com/systemdesign/googlenews/`.

---

## Table of Contents

1. [Problem Statement & Requirements](#1-problem-statement--requirements)
2. [Capacity Estimation](#2-capacity-estimation)
3. [Core Entities & Data Model](#3-core-entities--data-model)
4. [API Design](#4-api-design)
5. [High-Level Architecture](#5-high-level-architecture)
6. [Deep Dive: Feed Ingestion & Crawling](#6-deep-dive-feed-ingestion--crawling)
7. [Deep Dive: Article Ranking & Personalization](#7-deep-dive-article-ranking--personalization)
8. [Deep Dive: Search & Indexing](#8-deep-dive-search--indexing)
9. [Deep Dive: Caching Strategy](#9-deep-dive-caching-strategy)
10. [Deep Dive: Availability & Resilience](#10-deep-dive-availability--resilience)
11. [Deep Dive: Scaling](#11-deep-dive-scaling)
12. [Trade-offs & Alternatives](#12-trade-offs--alternatives)

---

## 1. Problem Statement & Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| FR-1 | `GET /feed` — returns personalized news feed for a user based on interests |
| FR-2 | `GET /articles/{id}` — retrieves full article details |
| FR-3 | `GET /search?q={query}` — searches articles by keyword, source, topic |
| FR-4 | `POST /interests` — user can follow topics, sources, keywords |
| FR-5 | System continuously crawls RSS/Atom feeds from thousands of news sources |
| FR-6 | Deduplication of similar articles from different sources |
| FR-7 | Trending articles surface based on velocity and engagement |

### Non-Functional Requirements

| Metric | Target |
|--------|--------|
| Scale | 500 million MAU, 10,000+ news sources |
| Articles published | ~100,000 articles/day globally |
| Feed latency (p95) | < 500 ms |
| Search latency (p95) | < 1 second |
| Crawl frequency | Every 5-15 minutes per source |
| Availability | 99.9% (≤ 8.7 hours downtime/year) |
| Article freshness | < 10 minutes from publication to feed |
| Data retention | 90 days for articles, indefinite for metadata |

### Out of Scope

- User authentication and user profile management (assumes user_id is provided)
- Video/multimedia content beyond article text and images
- Comments, social features, sharing
- Real-time push notifications
- Advanced NLP/ML for content understanding (uses keyword/topic tagging)

---

## 2. Capacity Estimation

### Storage Estimation

**Articles:**
- 100,000 articles/day × 365 days = 36.5M articles/year
- Average article size: 20 KB (title + summary + content + metadata)
- Annual storage: 36.5M × 20 KB = **730 GB/year**
- With 90-day retention: 730 GB / 4 = **~200 GB active storage**
- With replication (3×): **600 GB total**

**User Interests:**
- 500M users × 10 interests/user × 100 bytes = **500 GB**

**Metadata & Indices:**
- Sources, topics, deduplication hashes: **~50 GB**

**Total storage:** ~**1.2 TB** (primary + replicas)

### Traffic Estimation

**Read Traffic:**
- 500M MAU ÷ 30 days ÷ 86,400 seconds = **~190 active users/second**
- Average 5 feed requests/user/day = 2.5B requests/day
- Peak QPS: **~30,000 QPS** (assuming 2× average during peak hours)

**Write Traffic (Crawling):**
- 10,000 sources × 10 articles/source/day = 100,000 articles/day
- 100,000 ÷ 86,400 = **~1.2 writes/second** average
- Peak during news events: **~50 writes/second**

**Search Traffic:**
- 10% of users search daily: 50M searches/day
- 50M ÷ 86,400 = **~580 search QPS**

### Bandwidth Estimation

**Feed requests:**
- 30,000 QPS × 10 KB average response = **300 MB/s** = **2.4 Gbps**

**Crawling:**
- 10,000 sources × 20 KB/request × 1 request per 10 minutes = **~33 KB/s** inbound

**Cache efficiency:**
- With 80% cache hit rate: **60 MB/s** from cache, **240 MB/s** from DB/search

---

## 3. Core Entities & Data Model

### Article

```java
@Entity
@Table(name = "articles", indexes = {
    @Index(name = "idx_source_published", columnList = "source_id,published_at"),
    @Index(name = "idx_published", columnList = "published_at"),
    @Index(name = "idx_content_hash", columnList = "content_hash")
})
public class Article {
    @Id
    private String id;                    // UUID
    private String title;
    private String summary;               // First 300 chars or meta description
    private String content;               // Full article text
    private String url;                   // Original article URL
    private String imageUrl;
    
    @ManyToOne
    private Source source;
    
    @ManyToMany
    private Set<Topic> topics;            // "Technology", "Politics", etc.
    
    private Instant publishedAt;
    private Instant crawledAt;
    
    private String contentHash;           // SHA-256 for deduplication
    private Integer viewCount;            // For trending calculation
    private Integer engagementScore;      // Views + clicks + shares
    
    @Enumerated(EnumType.STRING)
    private ArticleStatus status;         // ACTIVE, DUPLICATE, REMOVED
}
```

### Source

```java
@Entity
@Table(name = "sources")
public class Source {
    @Id
    private String id;
    private String name;                  // "BBC News", "TechCrunch"
    private String feedUrl;               // RSS/Atom feed URL
    private String domain;                // "bbc.com"
    private FeedType feedType;            // RSS, ATOM
    
    private Integer crawlIntervalMinutes; // 5, 10, 15
    private Instant lastCrawledAt;
    private Boolean isActive;
    private Integer trustScore;           // 0-100, for ranking
}
```

### UserInterest

```java
@Entity
@Table(name = "user_interests", indexes = {
    @Index(name = "idx_user", columnList = "user_id")
})
public class UserInterest {
    @Id
    private String id;
    private String userId;
    
    @ManyToOne
    private Topic topic;                  // User follows a topic
    
    @ManyToOne
    private Source source;                // User follows a source
    
    private String keyword;               // User follows a keyword
    private Instant createdAt;
}
```

### Topic

```java
@Entity
@Table(name = "topics")
public class Topic {
    @Id
    private String id;
    private String name;                  // "Technology", "Sports"
    private String slug;                  // "technology"
    @ManyToOne
    private Topic parent;                 // Hierarchical topics
    private Integer followerCount;
}
```

---

## 4. API Design

### 4.1 Get Personalized Feed

**Request:**
```http
GET /api/v1/feed?userId={userId}&page=0&size=20
Authorization: Bearer {token}
```

**Response:**
```json
{
  "articles": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "title": "AI Breakthrough in Medical Diagnostics",
      "summary": "Researchers announce significant progress...",
      "source": {
        "id": "src_123",
        "name": "TechCrunch",
        "domain": "techcrunch.com"
      },
      "topics": ["Technology", "Healthcare"],
      "publishedAt": "2026-06-17T10:30:00Z",
      "imageUrl": "https://cdn.example.com/image123.jpg",
      "url": "https://techcrunch.com/article-url",
      "engagementScore": 15420
    }
  ],
  "page": 0,
  "totalPages": 150,
  "hasMore": true
}
```

**Query parameters:**
- `userId` (required): User identifier
- `page` (optional, default=0): Page number
- `size` (optional, default=20, max=100): Articles per page
- `topics` (optional): Comma-separated topic filters

### 4.2 Search Articles

**Request:**
```http
GET /api/v1/search?q=artificial+intelligence&topics=Technology&from=2026-06-01&to=2026-06-17&page=0&size=20
```

**Response:**
```json
{
  "results": [
    {
      "id": "article_id",
      "title": "...",
      "summary": "...",
      "highlights": {
        "title": ["<em>artificial</em> <em>intelligence</em>"],
        "content": ["breakthrough in <em>AI</em> research..."]
      },
      "source": {...},
      "relevanceScore": 0.92,
      "publishedAt": "2026-06-15T14:20:00Z"
    }
  ],
  "total": 1523,
  "page": 0,
  "took": 234
}
```

### 4.3 Get Article Details

**Request:**
```http
GET /api/v1/articles/{articleId}
```

**Response:**
```json
{
  "id": "article_id",
  "title": "...",
  "content": "Full article content...",
  "source": {...},
  "topics": [...],
  "publishedAt": "2026-06-17T10:30:00Z",
  "relatedArticles": [...]
}
```

### 4.4 Manage User Interests

**Add Interest:**
```http
POST /api/v1/users/{userId}/interests
Content-Type: application/json

{
  "type": "TOPIC",          // TOPIC, SOURCE, KEYWORD
  "value": "topic_id"       // or source_id or keyword string
}
```

**Remove Interest:**
```http
DELETE /api/v1/users/{userId}/interests/{interestId}
```

---

## 5. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                           Client Apps                                │
│                    (Web, iOS, Android)                               │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 ▼
         ┌───────────────┐
         │  CDN + WAF    │
         │  (CloudFlare) │
         └───────┬───────┘
                 │
                 ▼
    ┌────────────────────────────┐
    │   Load Balancer (ALB)      │
    └────────────┬───────────────┘
                 │
    ┌────────────┴──────────────┐
    │                           │
    ▼                           ▼
┌─────────────┐         ┌─────────────┐
│  API Tier   │         │  API Tier   │  (Auto-scaling)
│ (Spring)    │   ...   │ (Spring)    │
└──┬──────┬───┘         └──┬──────┬───┘
   │      │                 │      │
   │      └─────────┬───────┘      │
   │                │              │
   ▼                ▼              ▼
┌──────────┐  ┌──────────┐  ┌──────────┐
│  Redis   │  │  Redis   │  │  Redis   │  (Cluster)
│  Cache   │  │  Cache   │  │  Cache   │
└──────────┘  └──────────┘  └──────────┘
   │                │              │
   └────────────────┼──────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │   PostgreSQL         │
         │   (Primary)          │
         └──────┬───────────────┘
                │
         ┌──────┴───────┐
         │              │
         ▼              ▼
    ┌────────┐     ┌────────┐
    │Replica │     │Replica │
    └────────┘     └────────┘
         │
         └──────────────────┐
                            │
                            ▼
                  ┌──────────────────┐
                  │  Elasticsearch   │
                  │    Cluster       │
                  └──────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                     Background Services                              │
└─────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────┐         ┌──────────────────┐
│  Feed Crawler    │────────▶│  Message Queue   │
│  (Scheduled)     │         │  (RabbitMQ)      │
└──────────────────┘         └────────┬─────────┘
         │                             │
         │ Fetches RSS/Atom            │
         │                             ▼
         │                   ┌──────────────────┐
         │                   │ Article Processor│
         │                   │  (Dedupe, Parse) │
         │                   └────────┬─────────┘
         │                            │
         └────────────────────────────┼─────────▶ PostgreSQL
                                      │
                                      └─────────▶ Elasticsearch
```

### Key Components

1. **API Tier:** Stateless Spring Boot services handling user requests
2. **Redis Cache:** Distributed cache for feed results, article content, user interests
3. **PostgreSQL:** Primary data store for articles, sources, user interests
4. **Elasticsearch:** Full-text search index for article search
5. **Feed Crawler:** Scheduled job to poll RSS/Atom feeds from sources
6. **Message Queue:** Decouples crawling from article processing
7. **CDN:** Serves static assets (images, thumbnails) with geo-distribution

---

## 6. Deep Dive: Feed Ingestion & Crawling

### 6.1 Crawling Strategy

**Polling-Based Architecture:**

```java
@Service
public class FeedCrawlerService {
    
    @Scheduled(fixedRate = 60000) // Every minute
    public void crawlFeeds() {
        List<Source> sourcesToCrawl = sourceRepository
            .findByIsActiveAndNextCrawlTimeBefore(true, Instant.now());
        
        sourcesToCrawl.parallelStream()
            .forEach(this::crawlSource);
    }
    
    private void crawlSource(Source source) {
        try {
            SyndFeed feed = fetchFeed(source.getFeedUrl());
            
            feed.getEntries().forEach(entry -> {
                ArticleMessage msg = parseEntry(entry, source);
                messageQueue.send("article-ingestion", msg);
            });
            
            source.setLastCrawledAt(Instant.now());
            source.setNextCrawlTime(
                Instant.now().plus(source.getCrawlIntervalMinutes(), ChronoUnit.MINUTES)
            );
            sourceRepository.save(source);
            
        } catch (Exception e) {
            log.error("Failed to crawl source {}", source.getName(), e);
            // Exponential backoff logic
        }
    }
}
```

**Crawl Frequency Tuning:**
- High-traffic sources (CNN, BBC): Every 5 minutes
- Medium sources: Every 10 minutes
- Low-traffic sources: Every 15-30 minutes
- Adaptive: Increase frequency during breaking news events

### 6.2 Deduplication

**Content-Based Hashing:**

```java
@Service
public class ArticleProcessor {
    
    public void processArticle(ArticleMessage msg) {
        String contentHash = hashContent(msg.getTitle(), msg.getContent());
        
        Optional<Article> existing = articleRepository
            .findByContentHashAndPublishedAtAfter(
                contentHash, 
                Instant.now().minus(7, ChronoUnit.DAYS)
            );
        
        if (existing.isPresent()) {
            // Mark as duplicate, potentially merge metadata
            handleDuplicate(existing.get(), msg);
        } else {
            // Create new article
            Article article = createArticle(msg);
            article.setContentHash(contentHash);
            articleRepository.save(article);
            elasticsearchService.indexArticle(article);
        }
    }
    
    private String hashContent(String title, String content) {
        // Normalize: lowercase, remove punctuation, trim whitespace
        String normalized = normalize(title + " " + content);
        return DigestUtils.sha256Hex(normalized);
    }
}
```

**Fuzzy Deduplication:**
- Exact hash match: 100% duplicate
- Shingling/MinHash: 85%+ similarity → duplicate
- Title + first 200 chars similarity: 90%+ → likely duplicate

### 6.3 Topic Classification

**Simple Keyword-Based Tagging:**

```java
@Service
public class TopicClassifier {
    
    private static final Map<String, Set<String>> TOPIC_KEYWORDS = Map.of(
        "Technology", Set.of("ai", "software", "hardware", "tech", "startup"),
        "Politics", Set.of("election", "government", "president", "policy"),
        "Sports", Set.of("football", "basketball", "soccer", "olympics"),
        "Business", Set.of("market", "stock", "economy", "finance")
    );
    
    public Set<Topic> classifyArticle(Article article) {
        String text = (article.getTitle() + " " + article.getContent())
            .toLowerCase();
        
        return TOPIC_KEYWORDS.entrySet().stream()
            .filter(entry -> entry.getValue().stream()
                .anyMatch(text::contains))
            .map(entry -> topicRepository.findByName(entry.getKey()))
            .collect(Collectors.toSet());
    }
}
```

**Advanced Options:**
- ML-based classification (BERT, DistilBERT)
- Multi-label classification
- Named Entity Recognition (NER) for person/place/org tagging

---

## 7. Deep Dive: Article Ranking & Personalization

### 7.1 Personalized Feed Generation

**Two-Stage Ranking:**

**Stage 1: Candidate Retrieval**
```java
@Service
public class FeedService {
    
    public FeedResponse getPersonalizedFeed(String userId, Pageable pageable) {
        // Fetch user interests
        List<UserInterest> interests = userInterestRepository.findByUserId(userId);
        
        // Extract topics and sources
        Set<String> topicIds = extractTopicIds(interests);
        Set<String> sourceIds = extractSourceIds(interests);
        
        // Retrieve candidate articles (last 24 hours)
        List<Article> candidates = articleRepository
            .findByTopicsInOrSourceIdInAndPublishedAtAfter(
                topicIds,
                sourceIds,
                Instant.now().minus(24, ChronoUnit.HOURS),
                PageRequest.of(0, 500) // Top 500 candidates
            );
        
        // Stage 2: Rank candidates
        List<Article> ranked = rankArticles(candidates, userId, interests);
        
        return buildFeedResponse(ranked, pageable);
    }
}
```

**Stage 2: Ranking Algorithm**
```java
private List<Article> rankArticles(List<Article> articles, String userId, List<UserInterest> interests) {
    return articles.stream()
        .map(article -> {
            double score = calculateScore(article, userId, interests);
            return new ScoredArticle(article, score);
        })
        .sorted(Comparator.comparingDouble(ScoredArticle::getScore).reversed())
        .map(ScoredArticle::getArticle)
        .collect(Collectors.toList());
}

private double calculateScore(Article article, String userId, List<UserInterest> interests) {
    double score = 0.0;
    
    // Freshness (decay over time)
    long hoursOld = ChronoUnit.HOURS.between(article.getPublishedAt(), Instant.now());
    double freshnessScore = Math.exp(-0.1 * hoursOld); // Exponential decay
    score += freshnessScore * 10;
    
    // Source trust
    score += article.getSource().getTrustScore() * 0.5;
    
    // Topic relevance
    long matchingTopics = article.getTopics().stream()
        .filter(topic -> interests.stream()
            .anyMatch(interest -> interest.getTopic().equals(topic)))
        .count();
    score += matchingTopics * 5;
    
    // Engagement (trending)
    double engagementScore = Math.log1p(article.getEngagementScore());
    score += engagementScore * 2;
    
    // Diversity penalty (avoid topic saturation)
    // ... (check recent articles shown to user)
    
    return score;
}
```

### 7.2 Trending Articles

**Velocity-Based Trending:**

```java
@Service
public class TrendingService {
    
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void calculateTrending() {
        Instant last2Hours = Instant.now().minus(2, ChronoUnit.HOURS);
        
        List<Article> recentArticles = articleRepository
            .findByPublishedAtAfter(last2Hours);
        
        List<TrendingArticle> trending = recentArticles.stream()
            .map(article -> {
                double velocity = calculateVelocity(article);
                return new TrendingArticle(article, velocity);
            })
            .sorted(Comparator.comparingDouble(TrendingArticle::getVelocity).reversed())
            .limit(100)
            .collect(Collectors.toList());
        
        // Cache trending articles
        cacheService.set("trending:global", trending, Duration.ofMinutes(5));
    }
    
    private double calculateVelocity(Article article) {
        long minutesSincePublished = ChronoUnit.MINUTES.between(
            article.getPublishedAt(), Instant.now());
        
        if (minutesSincePublished == 0) minutesSincePublished = 1;
        
        double velocity = (double) article.getEngagementScore() / minutesSincePublished;
        
        // Boost from multiple sources covering same story
        int duplicateCount = articleRepository.countByContentHash(article.getContentHash());
        velocity *= (1 + Math.log1p(duplicateCount) * 0.5);
        
        return velocity;
    }
}
```

**Trending Topics:**
- Track topic mentions over time windows (1h, 6h, 24h)
- Identify topics with high velocity (mentions/hour)
- Surface in "Trending Topics" section

---

## 8. Deep Dive: Search & Indexing

### 8.1 Elasticsearch Indexing

**Index Mapping:**

```json
{
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "title": {
        "type": "text",
        "analyzer": "english",
        "fields": {
          "keyword": { "type": "keyword" }
        }
      },
      "content": {
        "type": "text",
        "analyzer": "english"
      },
      "summary": { "type": "text" },
      "source": {
        "properties": {
          "id": { "type": "keyword" },
          "name": { "type": "keyword" },
          "domain": { "type": "keyword" }
        }
      },
      "topics": { "type": "keyword" },
      "publishedAt": { "type": "date" },
      "engagementScore": { "type": "integer" }
    }
  }
}
```

**Indexing Strategy:**

```java
@Service
public class ElasticsearchService {
    
    public void indexArticle(Article article) {
        ArticleDocument doc = ArticleDocument.builder()
            .id(article.getId())
            .title(article.getTitle())
            .content(article.getContent())
            .summary(article.getSummary())
            .source(mapSource(article.getSource()))
            .topics(article.getTopics().stream()
                .map(Topic::getName)
                .collect(Collectors.toSet()))
            .publishedAt(article.getPublishedAt())
            .engagementScore(article.getEngagementScore())
            .build();
        
        elasticsearchOperations.save(doc);
    }
}
```

### 8.2 Search Query

**Multi-Field Search with Boosting:**

```java
@Service
public class SearchService {
    
    public SearchResponse search(SearchRequest request) {
        BoolQueryBuilder query = QueryBuilders.boolQuery();
        
        // Full-text search
        if (StringUtils.hasText(request.getQuery())) {
            query.should(QueryBuilders.multiMatchQuery(request.getQuery())
                .field("title", 3.0f)     // Boost title matches
                .field("content", 1.0f)
                .field("summary", 2.0f)
                .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)
                .fuzziness(Fuzziness.AUTO));
        }
        
        // Filter by topics
        if (!request.getTopics().isEmpty()) {
            query.filter(QueryBuilders.termsQuery("topics", request.getTopics()));
        }
        
        // Filter by date range
        if (request.getFrom() != null || request.getTo() != null) {
            RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery("publishedAt");
            if (request.getFrom() != null) rangeQuery.gte(request.getFrom());
            if (request.getTo() != null) rangeQuery.lte(request.getTo());
            query.filter(rangeQuery);
        }
        
        // Sort by relevance, then recency
        NativeSearchQuery searchQuery = NativeSearchQueryBuilder()
            .withQuery(query)
            .withPageable(PageRequest.of(request.getPage(), request.getSize()))
            .withSort(SortBuilders.scoreSort().order(SortOrder.DESC))
            .withSort(SortBuilders.fieldSort("publishedAt").order(SortOrder.DESC))
            .withHighlightFields(
                new HighlightBuilder.Field("title"),
                new HighlightBuilder.Field("content")
            )
            .build();
        
        SearchHits<ArticleDocument> hits = elasticsearchOperations.search(
            searchQuery, ArticleDocument.class);
        
        return mapToResponse(hits);
    }
}
```

### 8.3 Indexing Pipeline

**Async Indexing:**
- Articles indexed asynchronously after DB insert
- Use message queue to decouple indexing from write path
- Eventual consistency: article may not appear in search for ~1-2 seconds

**Reindexing:**
- Daily reindex of last 7 days (for schema changes)
- Zero-downtime reindex using index aliases

---

## 9. Deep Dive: Caching Strategy

### 9.1 Multi-Layer Cache

**L1: Application Cache (Caffeine)**
```java
@Configuration
public class CacheConfig {
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES));
        return manager;
    }
}
```

**L2: Distributed Cache (Redis)**

| Cache Key | Value | TTL | Invalidation |
|-----------|-------|-----|--------------|
| `feed:{userId}` | List<ArticleDTO> | 5 min | On new article published |
| `article:{articleId}` | ArticleDTO | 30 min | On article update |
| `trending:global` | List<ArticleDTO> | 5 min | Periodic refresh |
| `user:interests:{userId}` | Set<Interest> | 1 hour | On interest change |
| `search:{queryHash}` | SearchResponse | 10 min | Time-based only |

```java
@Service
public class CacheService {
    
    @Cacheable(value = "feed", key = "#userId")
    public FeedResponse getFeed(String userId, Pageable pageable) {
        // Cache miss → compute feed
        return feedService.generateFeed(userId, pageable);
    }
    
    @CacheEvict(value = "feed", allEntries = true)
    public void invalidateAllFeeds() {
        // Called when new articles published
    }
    
    @Cacheable(value = "article", key = "#articleId")
    public ArticleDTO getArticle(String articleId) {
        return articleRepository.findById(articleId)
            .map(this::toDTO)
            .orElseThrow();
    }
}
```

### 9.2 Cache Warming

**Pre-populate caches for popular queries:**

```java
@Scheduled(fixedRate = 300000) // Every 5 minutes
public void warmCache() {
    // Top 1000 active users
    List<String> topUsers = getTopActiveUsers(1000);
    
    topUsers.parallelStream().forEach(userId -> {
        try {
            feedService.getFeed(userId, PageRequest.of(0, 20));
        } catch (Exception e) {
            log.warn("Cache warming failed for user {}", userId);
        }
    });
}
```

### 9.3 Cache Invalidation Strategy

**Patterns:**
1. **Time-based:** All caches have TTL (5-60 minutes)
2. **Event-based:** Invalidate on write operations
3. **Lazy invalidation:** Accept stale data for non-critical paths

**Trade-off:**
- Shorter TTL → fresher data, higher DB load
- Longer TTL → lower latency, stale data

---

## 10. Deep Dive: Availability & Resilience

### 10.1 Database Replication

**Primary-Replica Setup:**
```
Primary (RW) ──┐
               ├──▶ Replica 1 (RO) ──▶ Feed queries
               ├──▶ Replica 2 (RO) ──▶ Search queries
               └──▶ Replica 3 (RO) ──▶ Analytics
```

**Read Routing:**
```java
@Configuration
public class DataSourceConfig {
    
    @Bean
    public DataSource routingDataSource() {
        RoutingDataSource routing = new RoutingDataSource();
        routing.setDefaultTargetDataSource(primaryDataSource());
        
        Map<Object, Object> dataSources = new HashMap<>();
        dataSources.put("primary", primaryDataSource());
        dataSources.put("replica", replicaDataSource());
        routing.setTargetDataSources(dataSources);
        
        return routing;
    }
}

@Transactional(readOnly = true)
@ReadOnlyReplica // Custom annotation to route to replica
public FeedResponse getFeed(String userId) {
    // Reads from replica
}
```

### 10.2 Circuit Breaker

**Resilience4j Configuration:**

```java
@Configuration
public class ResilienceConfig {
    
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(5)
            .build();
        
        return CircuitBreakerRegistry.of(config);
    }
}

@Service
public class ElasticsearchService {
    
    @CircuitBreaker(name = "elasticsearch", fallbackMethod = "searchFallback")
    public SearchResponse search(SearchRequest request) {
        return elasticsearchClient.search(request);
    }
    
    private SearchResponse searchFallback(SearchRequest request, Exception e) {
        log.error("Elasticsearch down, falling back to DB search", e);
        return databaseSearchService.search(request); // Slower but available
    }
}
```

### 10.3 Rate Limiting

**Per-User Rate Limiting:**

```java
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    
    private final RateLimiter rateLimiter = RateLimiter.of("api", RateLimiterConfig.custom()
        .limitForPeriod(100)           // 100 requests
        .limitRefreshPeriod(Duration.ofMinutes(1))
        .timeoutDuration(Duration.ofMillis(100))
        .build());
    
    @Override
    public boolean preHandle(HttpServletRequest request, ...) {
        String userId = extractUserId(request);
        
        boolean permitted = rateLimiter.acquirePermission();
        if (!permitted) {
            response.setStatus(429);
            return false;
        }
        return true;
    }
}
```

### 10.4 Graceful Degradation

**Feature Toggles:**

```java
@Service
public class FeedService {
    
    public FeedResponse getFeed(String userId, Pageable pageable) {
        if (featureFlags.isEnabled("personalization")) {
            return personalizedFeed(userId, pageable);
        } else {
            // Fallback to generic trending feed
            return trendingFeed(pageable);
        }
    }
}
```

---

## 11. Deep Dive: Scaling

### 11.1 Horizontal Scaling

**API Tier:**
- Stateless services → easy horizontal scaling
- Auto-scaling based on CPU/memory/request rate
- Target: 70% CPU utilization

```yaml
# Kubernetes HPA
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: googlenews-api
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: googlenews-api
  minReplicas: 5
  maxReplicas: 50
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

### 11.2 Database Scaling

**Sharding Strategy:**

**By User ID (for user interests):**
```
Shard 0: user_id % 10 == 0
Shard 1: user_id % 10 == 1
...
Shard 9: user_id % 10 == 9
```

**By Time (for articles):**
```
Shard 0: Jan-Mar 2026
Shard 1: Apr-Jun 2026
Shard 2: Jul-Sep 2026
Shard 3: Oct-Dec 2026
```

**Implementation:**
```java
@Service
public class ShardResolver {
    
    public int getUserShard(String userId) {
        return Math.abs(userId.hashCode()) % 10;
    }
    
    public int getArticleShard(Instant publishedAt) {
        // Time-based sharding
        LocalDate date = LocalDate.ofInstant(publishedAt, ZoneOffset.UTC);
        int quarter = (date.getMonthValue() - 1) / 3;
        return quarter; // 0-3
    }
}
```

### 11.3 Elasticsearch Scaling

**Index Strategy:**
- Time-based indices: `articles-2026-06`, `articles-2026-07`
- Index per month → easier to drop old indices
- Index aliases for querying across multiple indices

```bash
# Create monthly index
PUT /articles-2026-06
{
  "settings": {
    "number_of_shards": 5,
    "number_of_replicas": 2
  },
  "mappings": { ... }
}

# Update alias
POST /_aliases
{
  "actions": [
    { "add": { "index": "articles-2026-06", "alias": "articles-current" } }
  ]
}
```

### 11.4 Redis Scaling

**Redis Cluster:**
- 6-node cluster (3 primary, 3 replicas)
- Hash slot distribution: 16,384 slots
- Client-side routing

**Eviction Policy:**
```
maxmemory-policy: allkeys-lru
maxmemory: 4gb
```

---

## 12. Trade-offs & Alternatives

### 12.1 Polling vs. Push for Feed Ingestion

**Polling (Chosen):**
- ✅ Simple, works with any RSS/Atom feed
- ✅ No infrastructure from publishers
- ❌ Latency: 5-15 minute delay
- ❌ Wasted requests if no new articles

**Push (Alternative):**
- Use WebSub (formerly PubSubHubbub)
- Publishers notify subscribers of updates
- ✅ Real-time, efficient
- ❌ Requires publisher support (not universal)
- ❌ More complex infrastructure

### 12.2 Personalization Approach

**Keyword/Topic Matching (Chosen):**
- ✅ Simple, explainable, fast
- ✅ No ML infrastructure needed
- ❌ Less accurate than ML models
- ❌ Cold start problem for new users

**ML-Based Recommendations (Alternative):**
- Collaborative filtering, embeddings, deep learning
- ✅ Higher accuracy
- ✅ Discovers latent interests
- ❌ Requires large-scale ML infrastructure
- ❌ Training pipeline, feature engineering
- ❌ Black box (less explainable)

### 12.3 Search Technology

**Elasticsearch (Chosen):**
- ✅ Fast full-text search, highlighting, relevance scoring
- ✅ Rich query DSL
- ❌ Operational complexity
- ❌ Cost (memory-intensive)

**PostgreSQL Full-Text Search (Alternative):**
- Use `tsvector`, `tsquery`, GIN indices
- ✅ Simpler, one less system to manage
- ✅ ACID guarantees
- ❌ Slower for large datasets
- ❌ Limited features (no fuzzy search, complex scoring)

### 12.4 Cache Invalidation

**TTL-Based (Chosen):**
- Simple, predictable
- ❌ Can serve stale data
- ❌ Cache miss storms after expiry

**Event-Driven (Alternative):**
- Invalidate cache on every article insert
- ✅ Always fresh data
- ❌ Complex invalidation logic
- ❌ High invalidation rate → cache thrashing

**Hybrid Approach:**
- Short TTL (5 min) + event invalidation for critical paths
- Best of both worlds

### 12.5 Deduplication Strategy

**Content Hash (Chosen):**
- SHA-256 of normalized title + content
- ✅ Fast exact match detection
- ❌ Misses near-duplicates

**Fuzzy Matching (Alternative):**
- Shingling, MinHash, SimHash
- ✅ Detects near-duplicates (85%+ similarity)
- ❌ More complex, slower
- ❌ False positives

**When to Use Fuzzy:**
- High-value use case (e.g., premium content)
- Lower article volume
- Acceptable latency increase

---

## Summary

This Google News system design demonstrates:

1. **Scalable feed ingestion:** Polling-based crawler with deduplication
2. **Personalized ranking:** Two-stage retrieval + scoring based on interests, freshness, engagement
3. **Fast search:** Elasticsearch with multi-field boosting and highlighting
4. **High availability:** Redis caching, DB replication, circuit breakers
5. **Efficient scaling:** Horizontal API scaling, DB sharding, Redis cluster

**Key Metrics:**
- 500M MAU, 100K articles/day
- p95 feed latency < 500 ms
- 99.9% availability
- < 10 minute article freshness

**Implementation:** Complete Spring Boot codebase in `src/main/java/com/systemdesign/googlenews/`.
