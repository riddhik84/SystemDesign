# Google News — Code Walkthrough

> **Purpose:** This guide walks you through the Spring Boot implementation, explaining the architecture, key classes, and how data flows through the system. Read this to understand how the design document maps to actual code.

---

## Table of Contents

1. [Project Structure](#1-project-structure)
2. [Application Entry Point](#2-application-entry-point)
3. [Data Model Layer](#3-data-model-layer)
4. [Repository Layer](#4-repository-layer)
5. [Service Layer](#5-service-layer)
6. [Controller Layer](#6-controller-layer)
7. [Configuration](#7-configuration)
8. [Data Flow Examples](#8-data-flow-examples)
9. [Key Design Patterns](#9-key-design-patterns)
10. [Testing Strategy](#10-testing-strategy)

---

## 1. Project Structure

```
googlenews/
├── src/main/java/com/systemdesign/googlenews/
│   ├── GoogleNewsApplication.java          # Entry point
│   ├── model/                              # JPA entities
│   │   ├── Article.java
│   │   ├── Source.java
│   │   ├── Topic.java
│   │   └── UserInterest.java
│   ├── repository/                         # Data access layer
│   │   ├── ArticleRepository.java
│   │   ├── SourceRepository.java
│   │   ├── TopicRepository.java
│   │   └── UserInterestRepository.java
│   ├── service/                            # Business logic
│   │   ├── FeedService.java               # Feed generation
│   │   ├── RankingService.java            # Article scoring
│   │   ├── FeedCrawlerService.java        # RSS/Atom polling
│   │   ├── ArticleProcessorService.java   # Deduplication & storage
│   │   ├── TopicClassifierService.java    # Keyword-based tagging
│   │   └── UserInterestService.java       # Interest management
│   ├── controller/                         # REST endpoints
│   │   ├── FeedController.java
│   │   └── UserInterestController.java
│   ├── dto/                                # Data transfer objects
│   │   ├── ArticleDTO.java
│   │   ├── SourceDTO.java
│   │   ├── FeedResponse.java
│   │   └── InterestRequest.java
│   └── config/                             # Configuration classes
│       ├── CacheConfig.java                # Redis cache setup
│       ├── JpaConfig.java                  # JPA auditing
│       └── DataInitializer.java            # Seed data
├── src/main/resources/
│   └── application.yml                     # Spring Boot config
└── src/test/
    └── java/com/systemdesign/googlenews/
        └── GoogleNewsApplicationTests.java
```

**Key architectural layers:**
1. **Controllers** → REST API endpoints
2. **Services** → Business logic (feed generation, crawling, ranking)
3. **Repositories** → Database access (Spring Data JPA)
4. **Models** → JPA entities (Article, Source, Topic, UserInterest)
5. **Config** → Redis caching, JPA auditing, data initialization

---

## 2. Application Entry Point

### `GoogleNewsApplication.java`

```java
@SpringBootApplication
@EnableCaching        // Redis caching
@EnableScheduling     // Scheduled crawler
@EnableAsync          // Async crawling
public class GoogleNewsApplication {
    public static void main(String[] args) {
        SpringApplication.run(GoogleNewsApplication.class, args);
    }
}
```

**What this does:**
- `@EnableCaching`: Activates Spring Cache abstraction (backed by Redis)
- `@EnableScheduling`: Allows `@Scheduled` jobs (crawler runs every 60 seconds)
- `@EnableAsync`: Enables async method execution (parallel crawling)

**Why it matters:**
- Caching is critical for feed performance (80% hit rate)
- Scheduling enables continuous feed polling
- Async allows parallel processing of 10K+ sources

---

## 3. Data Model Layer

### `Article.java` — Core Entity

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
    private String summary;
    @Column(columnDefinition = "TEXT")
    private String content;
    private String url;
    private String imageUrl;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    private Source source;
    
    @ManyToMany
    @JoinTable(name = "article_topics", ...)
    private Set<Topic> topics;
    
    private Instant publishedAt;
    private Instant crawledAt;
    private String contentHash;           // SHA-256 for deduplication
    private Integer viewCount;
    private Integer engagementScore;
    
    @Enumerated(EnumType.STRING)
    private ArticleStatus status;         // ACTIVE, DUPLICATE, REMOVED
}
```

**Key design decisions:**

1. **Indices:**
   - `(source_id, published_at)`: Fetch recent articles from a source
   - `published_at`: Trending query (recent + high engagement)
   - `content_hash`: Deduplication lookup

2. **`FetchType.LAZY` on `Source`:**
   - Avoids N+1 queries when loading articles
   - Source data loaded only when accessed

3. **`contentHash` field:**
   - SHA-256 of normalized (lowercased, punctuation-removed) title + content
   - Enables O(1) duplicate detection

4. **`engagementScore`:**
   - Computed metric: views + clicks + shares
   - Used in trending calculation (velocity = engagement / time)

**Interview tip:** Explain why you chose each index. "We need `(source_id, published_at)` because the crawler fetches recent articles per source."

---

### `Source.java` — News Publisher

```java
@Entity
@Table(name = "sources", indexes = {
    @Index(name = "idx_next_crawl", columnList = "is_active,next_crawl_time")
})
public class Source {
    @Id
    private String id;
    private String name;                  // "TechCrunch"
    private String feedUrl;               // RSS/Atom URL
    private String domain;                // "techcrunch.com"
    
    @Enumerated(EnumType.STRING)
    private FeedType feedType;            // RSS, ATOM
    
    private Integer crawlIntervalMinutes; // 5, 10, 15
    private Instant lastCrawledAt;
    private Instant nextCrawlTime;        // When to crawl next
    private Boolean isActive;
    private Integer trustScore;           // 0-100, for ranking
    private Integer consecutiveFailures;  // Auto-disable after 5 failures
}
```

**Why `nextCrawlTime`?**
- Crawler queries: `findByIsActiveTrueAndNextCrawlTimeBefore(now)`
- Adaptive scheduling: Increase interval on failure (exponential backoff)
- Breaking news: Decrease interval during high-velocity events

**Interview tip:** Mention that `trustScore` allows editorial control over source quality (BBC=95, random blog=30).

---

### `UserInterest.java` — User Preferences

```java
@Entity
@Table(name = "user_interests", indexes = {
    @Index(name = "idx_user", columnList = "user_id")
})
public class UserInterest {
    @Id
    private String id;
    private String userId;
    
    @Enumerated(EnumType.STRING)
    private InterestType interestType;    // TOPIC, SOURCE, KEYWORD
    
    @ManyToOne(fetch = FetchType.LAZY)
    private Topic topic;                  // If type=TOPIC
    
    @ManyToOne(fetch = FetchType.LAZY)
    private Source source;                // If type=SOURCE
    
    private String keyword;               // If type=KEYWORD
    private Instant createdAt;
}
```

**Why three interest types?**
- **TOPIC:** User follows "Technology" → fetch all tech articles
- **SOURCE:** User follows "BBC" → fetch all BBC articles
- **KEYWORD:** User follows "AI" → keyword match in title/content

**Interview tip:** This design supports flexible personalization—users can mix interests (follow topics + sources + keywords).

---

## 4. Repository Layer

### `ArticleRepository.java`

```java
@Repository
public interface ArticleRepository extends JpaRepository<Article, String> {
    
    // Deduplication check
    Optional<Article> findByContentHashAndPublishedAtAfter(String contentHash, Instant after);
    
    // Candidate retrieval for personalized feed
    @Query("SELECT a FROM Article a WHERE " +
           "(a.topics IN :topics OR a.source.id IN :sourceIds) AND " +
           "a.publishedAt > :after AND a.status = 'ACTIVE' " +
           "ORDER BY a.publishedAt DESC")
    Page<Article> findCandidateArticles(
        @Param("topics") Set<Topic> topics,
        @Param("sourceIds") Set<String> sourceIds,
        @Param("after") Instant after,
        Pageable pageable
    );
    
    // Trending articles (recent + high engagement)
    @Query("SELECT a FROM Article a WHERE " +
           "a.publishedAt > :after AND a.status = 'ACTIVE' " +
           "ORDER BY a.engagementScore DESC")
    Page<Article> findTrendingArticles(@Param("after") Instant after, Pageable pageable);
    
    // Count duplicates for velocity calculation
    int countByContentHash(String contentHash);
}
```

**What's happening here:**

1. **Deduplication query:**
   - Before saving an article, check if a matching `contentHash` exists in the last 7 days
   - Avoids storing duplicates

2. **Candidate retrieval:**
   - Fetches articles matching user's topics OR sources
   - Filters to last 24 hours (freshness)
   - Returns top 500 (Stage 1 of two-stage ranking)

3. **Trending query:**
   - Orders by `engagementScore` descending
   - Filters to last 6 hours (trending = recent + popular)

**Interview tip:** Explain that you use `@Query` instead of method-name queries when the logic is complex (OR condition, multiple filters).

---

## 5. Service Layer

### `FeedService.java` — Feed Generation

**Key method: `getPersonalizedFeed()`**

```java
@Cacheable(value = "feed", key = "#userId + '_' + #pageable.pageNumber")
public FeedResponse getPersonalizedFeed(String userId, Pageable pageable) {
    // 1. Fetch user interests
    List<UserInterest> interests = userInterestRepository.findByUserId(userId);
    
    if (interests.isEmpty()) {
        return getTrendingFeed(pageable);  // Cold start: show trending
    }
    
    // 2. Extract topics and sources
    Set<Topic> topics = extractTopics(interests);
    Set<String> sourceIds = extractSourceIds(interests);
    
    // 3. Stage 1: Retrieve candidates (last 24 hours, matching interests)
    Page<Article> candidates = articleRepository.findCandidateArticles(
        topics, sourceIds, Instant.now().minus(24, ChronoUnit.HOURS), PageRequest.of(0, 500)
    );
    
    // 4. Stage 2: Rank candidates
    List<Article> rankedArticles = rankingService.rankArticles(
        candidates.getContent(), userId, interests
    );
    
    // 5. Paginate and return
    return buildFeedResponse(rankedArticles, pageable);
}
```

**Data flow:**
1. **Cache check:** Redis checks for `feed:{userId}_{page}`
2. **Cache miss → DB query:** Fetch 500 candidates matching interests
3. **Scoring:** Rank candidates by freshness + trust + relevance + engagement
4. **Pagination:** Return page 0 (20 articles)
5. **Cache write:** Store result in Redis (TTL = 5 min)

**Why 500 candidates?**
- Can't rank millions of articles in < 500 ms
- 500 is enough to provide diversity without overwhelming the scorer

**Interview tip:** Mention that this is a **two-stage retrieval-and-ranking** pattern, common in search/recommender systems.

---

### `RankingService.java` — Article Scoring

```java
private double calculateScore(Article article, List<UserInterest> interests) {
    double score = 0.0;
    
    // 1. Freshness (exponential decay)
    long hoursOld = ChronoUnit.HOURS.between(article.getPublishedAt(), Instant.now());
    double freshnessScore = Math.exp(-0.1 * hoursOld);
    score += freshnessScore * 10.0;
    
    // 2. Source trust
    score += article.getSource().getTrustScore() * 0.5;
    
    // 3. Topic relevance (count matching topics)
    long matchingTopics = article.getTopics().stream()
        .filter(topic -> interests.stream()
            .anyMatch(interest -> interest.getTopic().equals(topic)))
        .count();
    score += matchingTopics * 5.0;
    
    // 4. Engagement (logarithmic scale)
    double engagementScore = Math.log1p(article.getEngagementScore());
    score += engagementScore * 2.0;
    
    return score;
}
```

**Scoring breakdown:**

| Factor | Weight | Rationale |
|--------|--------|-----------|
| **Freshness** | 10× | News is time-sensitive; 24-hour-old article gets ~10% of fresh score |
| **Source trust** | 0.5× | BBC (95) ranks higher than random blog (30) |
| **Topic relevance** | 5× per match | User following "Tech" + article tagged "Tech" → +5 points |
| **Engagement** | 2× | Popular articles (high views/clicks) rank higher |

**Why exponential decay for freshness?**
- `exp(-0.1 * hours)`: Article loses ~10% score per hour
- After 24 hours: score ≈ 0.09 (9% of fresh score)

**Interview tip:** Explain that weights are tunable—you'd A/B test to find optimal values.

---

### `FeedCrawlerService.java` — RSS/Atom Polling

```java
@Scheduled(fixedRate = 60000)  // Every 60 seconds
@Transactional
public void crawlFeeds() {
    List<Source> sourcesToCrawl = sourceRepository
        .findByIsActiveTrueAndNextCrawlTimeBefore(Instant.now());
    
    sourcesToCrawl.parallelStream()  // Parallel for 10K sources
        .forEach(this::crawlSource);
}

private void crawlSource(Source source) {
    try {
        SyndFeed feed = fetchFeed(source.getFeedUrl());
        
        feed.getEntries().forEach(entry -> {
            articleProcessorService.processEntry(entry, source);
        });
        
        source.setNextCrawlTime(
            Instant.now().plus(source.getCrawlIntervalMinutes(), ChronoUnit.MINUTES)
        );
        source.setConsecutiveFailures(0);
        
    } catch (Exception e) {
        handleCrawlFailure(source);  // Exponential backoff
    }
}
```

**How it works:**
1. **Scheduled job runs every 60 seconds**
2. **Query:** Find sources where `next_crawl_time < now` AND `is_active = true`
3. **Parallel processing:** Use `parallelStream()` to crawl multiple sources concurrently
4. **Fetch feed:** HTTP GET to `feedUrl`, parse XML with Rome library
5. **Process entries:** For each article, check for duplicates, classify topics, save to DB
6. **Update `nextCrawlTime`:** `now + crawlIntervalMinutes`

**Why `parallelStream()`?**
- 10K sources × 500ms fetch = 5000 seconds sequentially
- Parallel: ~10 seconds (assuming 500 threads)

**Interview tip:** Mention that you'd use a message queue (RabbitMQ, Kafka) in production to decouple crawling from processing.

---

### `ArticleProcessorService.java` — Deduplication

```java
@CacheEvict(value = "feed", allEntries = true)
public void processEntry(SyndEntry entry, Source source) {
    // 1. Hash content
    String contentHash = hashContent(entry.getTitle(), getContent(entry));
    
    // 2. Deduplication check (last 7 days)
    Optional<Article> existing = articleRepository
        .findByContentHashAndPublishedAtAfter(contentHash, Instant.now().minus(7, ChronoUnit.DAYS));
    
    if (existing.isPresent()) {
        log.debug("Duplicate article: {}", entry.getTitle());
        return;  // Skip duplicate
    }
    
    // 3. Classify topics
    Set<Topic> topics = topicClassifierService.classifyArticle(entry.getTitle(), content);
    
    // 4. Save article
    Article article = Article.builder()
        .id(UUID.randomUUID().toString())
        .title(entry.getTitle())
        .contentHash(contentHash)
        .source(source)
        .topics(topics)
        .publishedAt(getPublishedDate(entry))
        .status(ArticleStatus.ACTIVE)
        .build();
    
    articleRepository.save(article);
}

private String hashContent(String title, String content) {
    String normalized = normalize(title + " " + content);  // Lowercase, remove punctuation
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return bytesToHex(digest.digest(normalized.getBytes()));
}
```

**Deduplication flow:**
1. **Normalize text:** Lowercase, remove punctuation, trim whitespace
2. **Hash:** SHA-256 of `title + content`
3. **DB lookup:** Check if hash exists in last 7 days
4. **If exists:** Skip (it's a duplicate)
5. **If new:** Classify topics, save to DB

**Why normalize before hashing?**
- "AI Breakthrough!" vs. "ai breakthrough" → same hash
- Catches duplicates with minor formatting differences

**Why 7-day window?**
- Balance: Longer window catches more dupes, but slower query
- News cycle: Story rarely reappears after 7 days

**Interview tip:** Mention that you'd use **fuzzy matching (MinHash)** for near-duplicates if exact hash isn't enough.

---

### `TopicClassifierService.java` — Keyword Tagging

```java
private static final Map<String, Set<String>> TOPIC_KEYWORDS = Map.of(
    "Technology", Set.of("ai", "software", "hardware", "tech", "startup"),
    "Politics", Set.of("election", "government", "president", "policy"),
    "Sports", Set.of("football", "basketball", "soccer", "olympics"),
    ...
);

public Set<Topic> classifyArticle(String title, String content) {
    String text = (title + " " + content).toLowerCase();
    
    return TOPIC_KEYWORDS.entrySet().stream()
        .filter(entry -> containsKeywords(text, entry.getValue()))
        .map(entry -> topicRepository.findByName(entry.getKey()))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toSet());
}
```

**How it works:**
1. **Concatenate title + content**
2. **For each topic:** Check if any keyword appears in text
3. **If match:** Add topic to article

**Limitations:**
- Simple keyword matching (no NLP)
- Misses context ("Apple" → Technology? or Food?)

**Interview tip:** Propose ML-based classification (BERT, DistilBERT) as a future enhancement.

---

## 6. Controller Layer

### `FeedController.java` — REST API

```java
@GetMapping("/feed")
public ResponseEntity<FeedResponse> getPersonalizedFeed(
        @RequestParam String userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    
    Pageable pageable = PageRequest.of(page, Math.min(size, 100));  // Cap at 100
    FeedResponse response = feedService.getPersonalizedFeed(userId, pageable);
    return ResponseEntity.ok(response);
}
```

**HTTP request:**
```
GET /api/v1/feed?userId=user123&page=0&size=20
```

**HTTP response:**
```json
{
  "articles": [ ... ],
  "page": 0,
  "totalPages": 150,
  "totalElements": 3000,
  "hasMore": true
}
```

**Why cap `size` at 100?**
- Prevents abuse (user requesting 1 million articles)
- Protects DB from large queries

---

## 7. Configuration

### `CacheConfig.java` — Redis Setup

```java
@Bean
public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(10));
    
    Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
        "feed", defaultConfig.entryTtl(Duration.ofMinutes(5)),
        "article", defaultConfig.entryTtl(Duration.ofMinutes(30)),
        "trending", defaultConfig.entryTtl(Duration.ofMinutes(5))
    );
    
    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(defaultConfig)
        .withInitialCacheConfigurations(cacheConfigurations)
        .build();
}
```

**Cache TTLs:**
- `feed`: 5 min (freshness matters)
- `article`: 30 min (content rarely changes)
- `trending`: 5 min (velocity changes quickly)

---

### `DataInitializer.java` — Seed Data

```java
@Component
public class DataInitializer implements CommandLineRunner {
    public void run(String... args) {
        initializeTopics();   // Create 8 topics (Technology, Politics, etc.)
        initializeSources();  // Create 4 sources (TechCrunch, BBC, etc.)
    }
}
```

**Why seed data?**
- App needs topics/sources to function
- Avoids manual SQL inserts

---

## 8. Data Flow Examples

### Example 1: Personalized Feed Request

**User action:** `GET /api/v1/feed?userId=user123&page=0&size=20`

**Flow:**
1. **FeedController** receives request
2. **FeedService.getPersonalizedFeed()** checks Redis cache for `feed:user123_0`
3. **Cache miss** → Query DB:
   - Fetch user interests: `SELECT * FROM user_interests WHERE user_id = 'user123'`
   - Extract topics: `['Technology', 'Sports']`, sources: `['bbc']`
   - Retrieve candidates: `SELECT * FROM articles WHERE topics IN ('Technology', 'Sports') OR source_id = 'bbc' AND published_at > now() - 24h LIMIT 500`
4. **RankingService** scores 500 articles
5. **Pagination:** Return top 20
6. **Cache write:** Store result in Redis (TTL = 5 min)
7. **Response:** JSON with 20 articles

**Latency breakdown:**
- Cache hit: ~10 ms
- Cache miss: ~200 ms (DB query + scoring)

---

### Example 2: Feed Crawl

**Scheduled job:** `FeedCrawlerService.crawlFeeds()` runs every 60 seconds

**Flow:**
1. **Query sources to crawl:** `SELECT * FROM sources WHERE is_active = true AND next_crawl_time < now()`
2. **Parallel fetch:** HTTP GET to each `feedUrl` (10K sources in parallel)
3. **Parse XML:** Rome library converts RSS/Atom to `SyndFeed` objects
4. **For each entry:**
   - Hash content → Check for duplicate
   - If new → Classify topics → Save to DB
   - Cache eviction: `@CacheEvict(value = "feed", allEntries = true)`
5. **Update source:** `next_crawl_time = now() + crawl_interval_minutes`

**Total time:** ~10-30 seconds (parallel fetching)

---

## 9. Key Design Patterns

### 1. Repository Pattern
- **Where:** `ArticleRepository`, `SourceRepository`
- **Why:** Abstracts data access, makes testing easier (mock repositories)

### 2. Service Layer Pattern
- **Where:** `FeedService`, `RankingService`, `ArticleProcessorService`
- **Why:** Separates business logic from HTTP layer

### 3. DTO Pattern
- **Where:** `ArticleDTO`, `FeedResponse`
- **Why:** Decouple API response from DB entities (avoid exposing all fields)

### 4. Cache-Aside Pattern
- **Where:** `@Cacheable` on `getPersonalizedFeed()`
- **Why:** App checks cache first, falls back to DB on miss

### 5. Two-Stage Retrieval
- **Where:** `FeedService.getPersonalizedFeed()`
- **Why:** Narrow candidates first (fast), then score (expensive)

### 6. Scheduled Job Pattern
- **Where:** `@Scheduled` on `crawlFeeds()`
- **Why:** Continuous polling without manual triggers

---

## 10. Testing Strategy

### Unit Tests
- **Test:** `RankingService.calculateScore()`
- **Mock:** Article, UserInterest
- **Verify:** Score calculation correctness

### Integration Tests
- **Test:** `FeedService.getPersonalizedFeed()`
- **Use:** H2 in-memory DB, embedded Redis
- **Verify:** End-to-end feed generation

### Load Tests
- **Tool:** JMeter, Gatling
- **Scenario:** 10K concurrent feed requests
- **Verify:** p95 latency < 500 ms

---

## Summary

**Key takeaways from the codebase:**

1. **Layered architecture:** Controllers → Services → Repositories → DB
2. **Caching:** Redis for feeds (5 min TTL), article content (30 min TTL)
3. **Two-stage ranking:** Retrieve 500 candidates → score → paginate
4. **Deduplication:** SHA-256 content hash, 7-day lookup window
5. **Scheduled crawling:** Every 60 seconds, parallel fetching
6. **Keyword classification:** Simple topic tagging (upgradeable to ML)

**How to navigate this codebase:**
- Start with `FeedController` → trace through `FeedService` → `RankingService`
- Read `FeedCrawlerService` to understand ingestion pipeline
- Check `ArticleRepository` custom queries to see DB optimization

**Next steps:**
- Run the app: `mvn spring-boot:run`
- Hit the API: `curl http://localhost:8080/api/v1/trending`
- Check Swagger UI: `http://localhost:8080/swagger-ui.html`
