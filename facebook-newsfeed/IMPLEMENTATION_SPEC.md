# Facebook News Feed — Implementation Spec (Authoritative Contract)

> This document is the single source of truth for implementing the Facebook News Feed
> Spring Boot module. It adapts the **Instagram** module (same hybrid-fanout problem) with two
> key differences: (1) **no media-upload machinery** — a post is plain `content` + optional
> `mediaUrl`; (2) a **read-time RANKING layer** driven by engagement + recency + affinity.
>
> Base package: `com.systemdesign.newsfeed`
> Runtime datasource: **H2 in-memory** (no external DB required). Redis is optional and
> **degrades gracefully** (every op try/catch; feed reads fall back to DB cold-start).
>
> Conventions inherited verbatim from Instagram:
> - Entities use Lombok `@Data @NoArgsConstructor`; `@Id @GeneratedValue(strategy = GenerationType.UUID)`; `@PrePersist` to set `createdAt`.
> - DTOs use Lombok `@Data` (plain POJOs, field-only).
> - Services/caches use **constructor injection** (no `@Autowired` on fields), SLF4J `Logger` via `LoggerFactory`.
> - Fanout is `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async("fanoutExecutor")`, publishing a `PostCreatedEvent` from `PostService.createPost`.
> - Redis feed is a ZSET (score = `createdAt` epoch millis), capped to `max-cached-feed`, TTL 1 day.
> - Ranking is applied at READ time (chronological fanout, ranking as a read-time layer).

---

## 1. File Manifest

### Main sources — under `src/main/java/com/systemdesign/newsfeed/`

```
NewsFeedApplication.java                         (EXISTS — keep as-is: @SpringBootApplication @EnableCaching @EnableAsync)

model/User.java                                  (entity)
model/Post.java                                  (entity — text content + optional mediaUrl + likeCount + commentCount)
model/Follow.java                                (entity)

repository/UserRepository.java
repository/PostRepository.java
repository/FollowRepository.java

dto/CreateUserRequest.java
dto/UserResponse.java
dto/CreatePostRequest.java
dto/PostResponse.java
dto/FeedResponse.java

event/PostCreatedEvent.java

cache/FeedCacheService.java
cache/PostCacheService.java

service/UserService.java
service/FollowService.java
service/PostService.java
service/FanoutService.java
service/RankingService.java                      (NEW — read-time ranking layer)
service/FeedService.java

controller/UserController.java
controller/PostController.java
controller/FeedController.java
controller/GlobalExceptionHandler.java

config/RedisConfig.java
config/AsyncConfig.java
config/DataInitializer.java
```

### Resources

```
src/main/resources/application.yml               (REPLACE — H2 in-memory; see §11)
src/test/resources/application.yml                (NEW — test profile; H2 + dead Redis port; see §11)
```

### Test sources — under `src/test/java/com/systemdesign/newsfeed/`

```
service/FeedServiceTest.java                      (hybrid merge + ranking order)
service/FanoutServiceTest.java                    (celebrity skipped)
service/FollowServiceTest.java                    (threshold flip up/down)
service/RankingServiceTest.java                   (engagement/recency ordering — plain Mockito unit test)
service/PostServiceTest.java                      (validation + like/comment counters + fanout event)
```

### Explicitly DROPPED vs Instagram (do NOT create)

`model/Media.java`, `model/MediaType.java`, `model/MediaStatus.java`, `repository/MediaRepository.java`,
`service/MediaService.java`, `controller/MediaController.java`, `dto/UploadUrlRequest.java`,
`dto/UploadUrlResponse.java`, `service/MediaServiceTest.java`.

### pom.xml change (see §12)

Move the H2 dependency from `<scope>test</scope>` to `<scope>runtime</scope>`. Keep PostgreSQL (harmless).

---

## 2. Entities

All entities: `package com.systemdesign.newsfeed.model;`, `import jakarta.persistence.*;`,
Lombok `@Data @NoArgsConstructor`.

### 2.1 `User` (identical to Instagram)

```java
@Entity
@Table(name = "users", indexes = { @Index(name = "idx_user_username", columnList = "username") })
@Data
@NoArgsConstructor
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String email;

    @Column(length = 500)
    private String bio;

    private String profilePictureUrl;

    @Column(nullable = false)
    private long followerCount = 0;

    @Column(nullable = false)
    private long followingCount = 0;

    @Column(nullable = false)
    private long postCount = 0;

    @Column(name = "is_celebrity", nullable = false)
    private boolean celebrity = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }
}
```

### 2.2 `Post` (NEWSFEED-SPECIFIC — no media collection)

```java
@Entity
@Table(name = "posts", indexes = {
    @Index(name = "idx_post_author", columnList = "author_id"),
    @Index(name = "idx_post_created", columnList = "created_at")
})
@Data
@NoArgsConstructor
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "author_id", nullable = false)
    private String authorId;

    @Column(length = 2200)
    private String content;                 // plain text body

    @Column(name = "media_url")
    private String mediaUrl;                 // OPTIONAL single URL, nullable

    @Column(name = "like_count", nullable = false)
    private int likeCount = 0;

    @Column(name = "comment_count", nullable = false)
    private int commentCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }
}
```

Notes: NO `mediaType`, NO `@ElementCollection` media urls, NO `post_media_urls` table.

### 2.3 `Follow` (identical to Instagram)

```java
@Entity
@Table(name = "follows",
    uniqueConstraints = { @UniqueConstraint(columnNames = {"follower_id", "followee_id"}) },
    indexes = {
        @Index(name = "idx_follow_follower", columnList = "follower_id"),
        @Index(name = "idx_follow_followee", columnList = "followee_id")
    })
@Data
@NoArgsConstructor
public class Follow {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "follower_id", nullable = false)
    private String followerId;

    @Column(name = "followee_id", nullable = false)
    private String followeeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }
}
```

---

## 3. Repositories

`package com.systemdesign.newsfeed.repository;` — all annotated `@Repository`, extend `JpaRepository<T, String>`.

### 3.1 `UserRepository extends JpaRepository<User, String>` (identical to Instagram)

```java
Optional<User> findByUsername(String username);

@Modifying @Transactional
@Query("UPDATE User u SET u.followerCount = u.followerCount + :delta WHERE u.id = :id")
void incrementFollowerCount(@Param("id") String id, @Param("delta") long delta);

@Modifying @Transactional
@Query("UPDATE User u SET u.followingCount = u.followingCount + :delta WHERE u.id = :id")
void incrementFollowingCount(@Param("id") String id, @Param("delta") long delta);

@Modifying @Transactional
@Query("UPDATE User u SET u.postCount = u.postCount + :delta WHERE u.id = :id")
void incrementPostCount(@Param("id") String id, @Param("delta") long delta);

@Modifying @Transactional
@Query("UPDATE User u SET u.celebrity = :flag WHERE u.id = :id")
void setCelebrity(@Param("id") String id, @Param("flag") boolean flag);

@Query("SELECT u.followerCount FROM User u WHERE u.id = :id")
Long getFollowerCount(@Param("id") String id);

@Modifying @Transactional
@Query("UPDATE User u SET u.celebrity = :flag WHERE u.id = :id AND u.followerCount >= :threshold AND u.celebrity != :flag")
int setCelebrityIfCountAboveThreshold(@Param("id") String id, @Param("threshold") long threshold, @Param("flag") boolean flag);

@Modifying @Transactional
@Query("UPDATE User u SET u.celebrity = :flag WHERE u.id = :id AND u.followerCount < :threshold AND u.celebrity != :flag")
int setCelebrityIfCountBelowThreshold(@Param("id") String id, @Param("threshold") long threshold, @Param("flag") boolean flag);
```
Imports: `org.springframework.data.jpa.repository.{Modifying,Query}`, `org.springframework.data.repository.query.Param`, `org.springframework.transaction.annotation.Transactional`, `java.util.Optional`.

### 3.2 `PostRepository extends JpaRepository<Post, String>` (Instagram queries + NEW counter updates)

```java
List<Post> findByAuthorIdOrderByCreatedAtDesc(String authorId, Pageable pageable);

List<Post> findByAuthorIdInOrderByCreatedAtDesc(Collection<String> authorIds, Pageable pageable);

List<Post> findByAuthorIdInAndCreatedAtAfterOrderByCreatedAtDesc(
    Collection<String> authorIds, LocalDateTime since, Pageable pageable);

// NEW — engagement counter bumps (drive ranking signals)
@Modifying @Transactional
@Query("UPDATE Post p SET p.likeCount = p.likeCount + :delta WHERE p.id = :id")
void incrementLikeCount(@Param("id") String id, @Param("delta") long delta);

@Modifying @Transactional
@Query("UPDATE Post p SET p.commentCount = p.commentCount + :delta WHERE p.id = :id")
void incrementCommentCount(@Param("id") String id, @Param("delta") long delta);
```
Imports: `org.springframework.data.domain.Pageable`, `org.springframework.data.jpa.repository.{JpaRepository,Modifying,Query}`, `org.springframework.data.repository.query.Param`, `org.springframework.transaction.annotation.Transactional`, `java.time.LocalDateTime`, `java.util.{Collection,List}`.

### 3.3 `FollowRepository extends JpaRepository<Follow, String>` (identical to Instagram)

```java
Optional<Follow> findByFollowerIdAndFolloweeId(String followerId, String followeeId);
boolean existsByFollowerIdAndFolloweeId(String followerId, String followeeId);
long countByFolloweeId(String followeeId);

@Query("SELECT f.followeeId FROM Follow f WHERE f.followerId = :uid")
List<String> findFolloweeIds(@Param("uid") String uid);

@Query("SELECT f.followerId FROM Follow f WHERE f.followeeId = :uid")
List<String> findFollowerIds(@Param("uid") String uid);

@Modifying @Transactional
void deleteByFollowerIdAndFolloweeId(String followerId, String followeeId);
```

---

## 4. DTOs

`package com.systemdesign.newsfeed.dto;` — all Lombok `@Data`.

### 4.1 `CreateUserRequest`
```java
private String username;
private String displayName;
private String email;
private String bio;
```

### 4.2 `UserResponse`
```java
private String id;
private String username;
private String displayName;
private String bio;
private String profilePictureUrl;
private long followerCount;
private long followingCount;
private long postCount;
private boolean celebrity;
```

### 4.3 `CreatePostRequest`  (NO mediaType, NO mediaIds)
```java
private String authorId;
private String content;
private String mediaUrl;   // optional
```

### 4.4 `PostResponse`  (adds engagement + per-viewer ranking score)
```java
private String id;
private String authorId;
private String authorUsername;
private String content;
private String mediaUrl;
private int likeCount;
private int commentCount;
private String createdAt;   // Post.createdAt.toString()
private Double score;       // per-viewer ranking score; null for non-feed reads (e.g. GET /posts/{id})
```
Note: `score` is nullable so a plain `getPost` cache round-trip carries no score, while a feed
response carries the viewer-specific score. Jackson (the `ObjectMapper` bean) serializes it fine.

### 4.5 `FeedResponse` (identical shape to Instagram)
```java
private String userId;
private List<PostResponse> posts;
private int page;
private int pageSize;
private boolean hasMore;
private long servedAtEpochMs;
private String feedStrategyNote;
```

---

## 5. Event

`package com.systemdesign.newsfeed.event;` — identical to Instagram.

```java
@Getter
public class PostCreatedEvent extends ApplicationEvent {
    private final Post post;
    public PostCreatedEvent(Object source, Post post) { super(source); this.post = post; }
}
```
Imports: `com.systemdesign.newsfeed.model.Post`, `lombok.Getter`, `org.springframework.context.ApplicationEvent`.

---

## 6. Cache Services

`package com.systemdesign.newsfeed.cache;` — `@Service`, constructor injection of
`StringRedisTemplate redis` and `ObjectMapper objectMapper`, SLF4J logger. **Every Redis op wrapped
in try/catch**; on failure log `warn` and return empty/no-op (graceful degradation).

### 6.1 `FeedCacheService`

- Constant: `private static final String FEED_PREFIX = "feed:";`  → key `feed:{userId}`
- `@Value("${app.cache.feed-ttl-seconds:86400}") long feedTtlSeconds;`
- `@Value("${app.feed.max-cached-feed:1000}") int maxCachedFeed;`

Methods (exact signatures):
```java
void addToFeed(String userId, String postId, long scoreMs)
void addToManyFeeds(Collection<String> userIds, String postId, long scoreMs)
List<String> getFeedPostIds(String userId, int offset, int limit)
long feedSize(String userId)
```
Semantics (mirror Instagram exactly):
- `addToFeed`: `redis.opsForZSet().add(key, postId, scoreMs)` (score = createdAt epoch millis);
  then `zCard`; if size > `maxCachedFeed`, `removeRange(key, 0, size - maxCachedFeed - 1)` to keep
  newest N; then `redis.expire(key, Duration.ofSeconds(feedTtlSeconds))`.
- `addToManyFeeds`: loop calling `addToFeed`.
- `getFeedPostIds`: `redis.opsForZSet().reverseRange(key, offset, offset + limit - 1)`
  (reverse-chronological); null → empty list; return `new ArrayList<>(set)`.
- `feedSize`: `zCard`, null → 0.
- All catch `Exception` → log warn, return empty list / 0 / no-op.

### 6.2 `PostCacheService`

- Constant: `private static final String POST_PREFIX = "post:";`  → key `post:{postId}`
- `@Value("${app.cache.post-ttl-seconds:3600}") long postTtlSeconds;`

Methods:
```java
Optional<PostResponse> get(String postId)   // read-through; JSON via objectMapper; miss/failure → Optional.empty()
void put(PostResponse post)                  // objectMapper.writeValueAsString; set with Duration.ofSeconds(postTtlSeconds)
void evict(String postId)                    // redis.delete(POST_PREFIX + postId)
```
All wrapped in try/catch → log warn on failure. `get` returns `Optional.empty()` on any failure.

---

## 7. Services

`package com.systemdesign.newsfeed.service;` — `@Service`, constructor injection, SLF4J logger.

### 7.1 `UserService`

Deps: `UserRepository`.
```java
@Transactional public User createUser(CreateUserRequest req)
public User getUser(String id)
public UserResponse getUserResponse(String id)
public UserResponse toResponse(User user)
```
- `createUser`: reject duplicate username (`findByUsername(...).isPresent()` → `IllegalStateException("Username already exists: " + username)`); set username/displayName/email/bio; save; log; return entity.
- `getUser`: `findById(id).orElseThrow(() -> new NoSuchElementException("User not found: " + id))`.
- `getUserResponse`: `toResponse(getUser(id))`.
- `toResponse`: copy all `UserResponse` fields (celebrity via `user.isCelebrity()`).

### 7.2 `FollowService`

Deps: `FollowRepository`, `UserRepository`, `UserService`.
`@Value("${app.feed.celebrity-follower-threshold}") long celebrityFollowerThreshold;`
```java
@Transactional public void follow(String followerId, String followeeId)
@Transactional public void unfollow(String followerId, String followeeId)
public List<String> getFolloweeIds(String userId)
public List<String> getFollowerIds(String userId)
public boolean isCelebrity(String userId)
```
- `follow`: reject self-follow (`IllegalArgumentException("cannot follow yourself")`); validate both users exist via `userService.getUser`; reject duplicate (`existsByFollowerIdAndFolloweeId` → `IllegalStateException("already following user: " + followeeId)`); save `Follow`; `incrementFollowerCount(followeeId, 1)`, `incrementFollowingCount(followerId, 1)`; then `setCelebrityIfCountAboveThreshold(followeeId, threshold, true)` — if `>0`, log became-celebrity.
- `unfollow`: if relationship exists → `deleteByFollowerIdAndFolloweeId`; `incrementFollowerCount(followeeId, -1)`, `incrementFollowingCount(followerId, -1)`; `setCelebrityIfCountBelowThreshold(followeeId, threshold, false)` — if `>0`, log lost-celebrity. Idempotent no-op if not following.
- `getFolloweeIds` / `getFollowerIds`: delegate to repo.
- `isCelebrity`: `userService.getUser(userId).isCelebrity()`.

### 7.3 `PostService`

Deps: `PostRepository`, `UserRepository`, `UserService`, `PostCacheService`, `ApplicationEventPublisher`.
(NO MediaService.)
```java
@Transactional public PostResponse createPost(CreatePostRequest req)
public PostResponse getPost(String postId)
@Transactional public PostResponse likePost(String postId)
@Transactional public PostResponse commentPost(String postId)
public List<Post> getRecentPostsByAuthors(Collection<String> authorIds, LocalDateTime since, int limit)
public PostResponse toResponse(Post post)
```
- `createPost`:
  1. `userService.getUser(req.getAuthorId())` (validates author; NoSuchElementException → 404).
  2. Build `Post`: set `authorId`, `content`, `mediaUrl` (may be null). `likeCount`/`commentCount` default 0.
  3. `postRepository.save(post)`.
  4. `userRepository.incrementPostCount(authorId, 1)`.
  5. `PostResponse response = toResponse(saved)`; `postCacheService.put(response)`.
  6. `eventPublisher.publishEvent(new PostCreatedEvent(this, saved))` (triggers async fanout AFTER commit).
  7. log; return `response`.
- `getPost`: cache-aside — `postCacheService.get(postId).orElseGet(() -> { Post p = findById... orElseThrow NoSuchElementException("Post not found: " + postId); PostResponse r = toResponse(p); postCacheService.put(r); return r; })`.
- `likePost`: validate exists (`findById(postId).orElseThrow(NoSuchElementException)`); `postRepository.incrementLikeCount(postId, 1)`; `postCacheService.evict(postId)`; return `getPost(postId)` (re-reads fresh, re-caches). Log.
- `commentPost`: same as `likePost` but `incrementCommentCount(postId, 1)`.
- `getRecentPostsByAuthors`: if `authorIds.isEmpty()` return `List.of()`; else `findByAuthorIdInAndCreatedAtAfterOrderByCreatedAtDesc(authorIds, since, PageRequest.of(0, limit))`.
- `toResponse`: map id/authorId/content/mediaUrl/likeCount/commentCount; `createdAt = post.getCreatedAt().toString()`; resolve `authorUsername` via `userRepository.findById(authorId).map(User::getUsername).orElse(authorId)`; leave `score` null.

### 7.4 `FanoutService`

Deps: `FollowService`, `UserService`, `FeedCacheService`.
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async("fanoutExecutor")
public void onPostCreated(PostCreatedEvent event)          // -> fanoutSync(event.getPost())

public void fanoutSync(Post post)                          // synchronous core (deterministic in tests)
```
- `fanoutSync`:
  1. If `followService.isCelebrity(post.getAuthorId())` → log skip, `return` (celebrity handled fanout-on-read).
  2. `followerIds = followService.getFollowerIds(authorId)`; if empty → log debug, return.
  3. `long scoreMs = post.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();`
  4. `feedCacheService.addToManyFeeds(followerIds, post.getId(), scoreMs)`; log.
- Imports: `org.springframework.scheduling.annotation.Async`, `org.springframework.transaction.event.{TransactionPhase,TransactionalEventListener}`, `java.time.ZoneOffset`.

### 7.5 `RankingService`  (NEW — read-time relevance layer)

Deps: `PostService` (for `toResponse`), `FollowService` (for affinity).
`@Value("${app.ranking.half-life-hours:6}") double halfLifeHours;`

Public API:
```java
// Rank candidate posts for a given viewer, DESC by score. Sets PostResponse.score.
public List<PostResponse> rank(String viewerId, List<Post> candidates)

// Score a single post for a viewer (exposed for unit testing).
public double score(String viewerId, Post post, Set<String> followeeIds)
```

Behavior of `rank`:
1. If `candidates` empty → return `new ArrayList<>()`.
2. `Set<String> followeeIds = new HashSet<>(followService.getFolloweeIds(viewerId));` (single lookup).
3. For each candidate: `double s = score(viewerId, post, followeeIds)`; `PostResponse r = postService.toResponse(post)`; `r.setScore(s)`; collect.
4. Sort the resulting `List<PostResponse>` by `score` DESC (`Comparator.comparingDouble(PostResponse::getScore).reversed()`); on ties, secondary sort by `createdAt` DESC (string compare of ISO timestamp is chronologically correct) for determinism.
5. Return the sorted list.

**Concrete deterministic scoring formula** (`score`):
```
score = recencyDecay(ageHours) * engagementBoost(likeCount, commentCount) * affinity(viewer, author)
```
where:
- `ageHours = max(0, (now - post.createdAt) in milliseconds / 3_600_000.0)`
  (use `Duration.between(post.getCreatedAt(), LocalDateTime.now()).toMillis()`).
- `recencyDecay = Math.exp(-ageHours / halfLifeHours)`  (monotonic ↓ with age; ∈ (0,1]).
- `engagementBoost = 1.0 + Math.log1p(post.getLikeCount() + 2.0 * post.getCommentCount())`
  (comments weighted 2×; `log1p` dampens; ≥ 1.0).
- `affinity`: `followeeIds.contains(post.getAuthorId()) ? 1.5 : 1.0`
  (viewer directly following the author boosts relevance).

Properties the implementation must preserve (verified by RankingServiceTest):
- For equal age & affinity, higher `likeCount`/`commentCount` ⇒ strictly higher score.
- For equal engagement & affinity, more recent post (smaller ageHours) ⇒ strictly higher score.
- For equal age & engagement, a followed author ⇒ higher score than a non-followed author.

Imports: `java.time.{Duration,LocalDateTime}`, `java.util.{ArrayList,Comparator,HashSet,List,Set}`.

### 7.6 `FeedService`  (hybrid merge → ranking → paginate)

Deps: `FollowService`, `UserService`, `PostService`, `FeedCacheService`, `RankingService`, `PostRepository`.
```java
@Value("${app.feed.page-size:20}") int defaultPageSize;
@Value("${app.feed.celebrity-lookback-hours:24}") int celebrityLookbackHours;
@Value("${app.feed.max-cached-feed:1000}") int maxCachedFeed;

public FeedResponse getFeed(String userId, int page, int pageSize)
```
Flow:
1. `long startMs = System.currentTimeMillis();`
2. `userService.getUser(userId)` (404 if missing).
3. `List<String> followeeIds = followService.getFolloweeIds(userId);` if empty → `buildEmptyFeed(userId, page, pageSize)` with note `"EMPTY_FEED (user follows nobody)"`.
4. **Fanout-on-write source:** `List<String> precomputedPostIds = feedCacheService.getFeedPostIds(userId, 0, maxCachedFeed);`
5. **Fanout-on-read source (celebrities):**
   `List<String> celebrityFolloweeIds = followeeIds.stream().filter(followService::isCelebrity).collect(toList());`
   `LocalDateTime celebrityCutoff = LocalDateTime.now().minusHours(celebrityLookbackHours);`
   `List<Post> celebrityPosts = celebrityFolloweeIds.isEmpty() ? List.of() : postService.getRecentPostsByAuthors(celebrityFolloweeIds, celebrityCutoff, pageSize * 3 + 1);`
6. **Cold-start fallback:** `boolean coldStart = precomputedPostIds.isEmpty();`
   if coldStart → `fallbackPosts = postRepository.findByAuthorIdInOrderByCreatedAtDesc(followeeIds, PageRequest.of(0, pageSize * 3));` (else `List.of()`).
7. **Merge & dedup** into `Map<String,Post> postMap = new LinkedHashMap<>();`
   add fallbackPosts, then celebrityPosts, then hydrate each precomputed id not already present via
   `postRepository.findById(id)` (wrap in try/catch, log warn on failure — skip nulls).
8. **Rank:** `List<PostResponse> ranked = rankingService.rank(userId, new ArrayList<>(postMap.values()));`
   (Replaces Instagram's plain createdAt-desc sort; ranking is the read-time relevance layer.)
9. **Paginate** the ranked list:
   `int startIdx = page * pageSize; int endIdx = min(startIdx + pageSize, ranked.size());`
   `List<PostResponse> pagePosts = startIdx < ranked.size() ? ranked.subList(startIdx, endIdx) : List.of();`
10. Build `FeedResponse`: userId, posts=pagePosts, page, pageSize,
    `hasMore = ranked.size() > startIdx + pageSize`, `servedAtEpochMs = System.currentTimeMillis()`,
    `feedStrategyNote = buildStrategyNote(...)`.
11. Log `userId page posts elapsedMs strategy`.

Private helpers (mirror Instagram):
- `buildStrategyNote(int precomputedCount, int celebrityCount, int fallbackCount, boolean coldStart, int celebrityFolloweeCount)`
  — assemble list of notes joined by `" + "`; must include the substring **`fallback`** when coldStart
  (e.g. `"COLD_START (precomputed empty, used DB fallback: N posts)"`), `"FANOUT_ON_WRITE (precomputed: N post IDs from Redis)"` when precomputed non-empty, `"FANOUT_ON_READ (celebrity: N posts from M celebrities, last Hh)"` when celebrityCount>0; append `" + RANKED (relevance: recency×engagement×affinity)"` to reflect the ranking layer; default `"EMPTY_FEED"`.
- `buildEmptyFeed(userId, page, pageSize)` — posts=`List.of()`, hasMore=false, servedAtEpochMs set, note `"EMPTY_FEED (user follows nobody)"`.

Imports: `org.springframework.data.domain.PageRequest`, `java.time.LocalDateTime`, `java.util.*`, `java.util.stream.Collectors`.

---

## 8. Controllers

`package com.systemdesign.newsfeed.controller;` — `@RestController`, constructor injection.

### 8.1 `UserController` — `@RequestMapping("/api/users")`  (follow shape identical to Instagram)

| Verb | Path | Params | Body | Returns |
|---|---|---|---|---|
| POST | `/api/users` | — | `CreateUserRequest` | `201 CREATED` `UserResponse` |
| GET | `/api/users/{userId}` | path `userId` | — | `UserResponse` |
| POST | `/api/users/{userId}/follow` | path `userId`, query `targetId` | — | `204 No Content` |
| DELETE | `/api/users/{userId}/follow` | path `userId`, query `targetId` | — | `204 No Content` |
| GET | `/api/users/{userId}/followers` | path `userId` | — | `List<String>` |
| GET | `/api/users/{userId}/following` | path `userId` | — | `List<String>` |

- `createUser`: validate `username`, `displayName`, `email` non-blank (`IllegalArgumentException` otherwise);
  `userService.getUserResponse(userService.createUser(req).getId())`; return `ResponseEntity.status(CREATED)`.
- `follow`/`unfollow`: validate `targetId` non-blank (`IllegalArgumentException`);
  call `followService.follow(userId, targetId)` / `unfollow(...)`; return `ResponseEntity.noContent().build()`.
  (Method signature: `@RequestParam String targetId`.)

### 8.2 `PostController` — `@RequestMapping("/api/posts")`

| Verb | Path | Params | Body | Returns |
|---|---|---|---|---|
| POST | `/api/posts` | — | `CreatePostRequest` | `201 CREATED` `PostResponse` |
| GET | `/api/posts/{postId}` | path `postId` | — | `PostResponse` |
| POST | `/api/posts/{postId}/like` | path `postId` | — | `PostResponse` (updated) |
| POST | `/api/posts/{postId}/comment` | path `postId` | — | `PostResponse` (updated) |

- `createPost`: validate `req.getAuthorId()` non-blank (`IllegalArgumentException("authorId is required")`);
  `req.getContent()` non-blank (`IllegalArgumentException("content is required")`);
  `postService.createPost(req)`; return `201`.
- `getPost`: `postService.getPost(postId)`.
- `like`: `postService.likePost(postId)` → 200 with updated `PostResponse`.
- `comment`: `postService.commentPost(postId)` → 200 with updated `PostResponse`.

### 8.3 `FeedController` — `@RequestMapping("/api/feed")`  (identical to Instagram)

| Verb | Path | Params | Returns |
|---|---|---|---|
| GET | `/api/feed` | query `userId`, `page` (default 0), `pageSize` (default 20) | `FeedResponse` |

- Validate `page >= 0 && pageSize > 0` and `pageSize <= 100` (`IllegalArgumentException` otherwise);
  `feedService.getFeed(userId, page, pageSize)`.

### 8.4 `GlobalExceptionHandler` — `@RestControllerAdvice`  (identical to Instagram)

- `NoSuchElementException` → `404 NOT_FOUND` `Map.of("error", e.getMessage())`.
- `IllegalStateException` → `409 CONFLICT`.
- `IllegalArgumentException` → `400 BAD_REQUEST`.

---

## 9. Config

`package com.systemdesign.newsfeed.config;`

### 9.1 `RedisConfig` (identical to Instagram)
```java
@Configuration
public class RedisConfig {
    @Bean public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) { return new StringRedisTemplate(factory); }
    @Bean public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
```

### 9.2 `AsyncConfig` (identical to Instagram)
- `@Configuration @EnableAsync implements AsyncConfigurer`.
- `@Bean(name = "fanoutExecutor") Executor fanoutExecutor()`: `ThreadPoolTaskExecutor` core=10, max=50,
  queueCapacity=1000, threadNamePrefix `"fanout-"`, `CallerRunsPolicy` rejection handler, `initialize()`.
- Override `getAsyncUncaughtExceptionHandler()` → log ERROR with method name + throwable.

### 9.3 `DataInitializer`  (`@Component @Profile("!test")`, `@PostConstruct init()`)

Deps: `UserService`, `FollowService`, `PostService`, `UserRepository`.
Seed (all inside a try/catch that logs errors, so a Redis-down environment still boots):
1. Create users: `alice`, `bob`, `carol`, `david`, and `celebrity` (via a `createUser(username, displayName, email, bio)` helper using `CreateUserRequest`).
2. Make `celebrity` a celebrity ABOVE the main-profile threshold (100000):
   `userRepository.setCelebrity(celebrity.getId(), true); userRepository.incrementFollowerCount(celebrity.getId(), 100001L);`
3. Follows (so feeds are non-empty and celebrity fanout-on-read is exercised):
   - alice → bob, carol, celebrity
   - bob → alice, celebrity
   - carol → alice, bob, david
   - david → alice, bob, carol, celebrity
4. Create posts via a `createTextPost(authorId, content, mediaUrlOrNull)` helper (calls `postService.createPost`):
   at least one per user; mix of `mediaUrl = null` (text-only) and a sample URL string
   (e.g. `"https://cdn.newsfeed.example.com/img/xyz.jpg"`); include a couple from `celebrity`.
5. **Seed engagement so ranking is demonstrable:** call `postService.likePost(...)` / `postService.commentPost(...)`
   several times on a subset of posts (e.g. bump one of bob's posts to ~50 likes / 10 comments, leave others low),
   capturing the returned `PostResponse.getId()` from `createTextPost` to target specific posts.
6. Log a summary line (users/follows/posts counts).

Note: NO media-upload calls (MediaService is dropped).

---

## 10. Application entrypoint

`NewsFeedApplication.java` **already exists** — keep verbatim:
```java
@SpringBootApplication
@EnableCaching
@EnableAsync
public class NewsFeedApplication { public static void main(String[] args) { SpringApplication.run(NewsFeedApplication.class, args); } }
```

---

## 11. Configuration files

### 11.1 `src/main/resources/application.yml` (REPLACE the current PostgreSQL config with this)

```yaml
spring:
  application:
    name: facebook-newsfeed

  datasource:
    url: jdbc:h2:mem:newsfeed
    driver-class-name: org.h2.Driver
    username: sa
    password:

  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.H2Dialect

  h2:
    console:
      enabled: true
      path: /h2-console

  data:
    redis:
      host: localhost
      port: 6379
      timeout: 500ms

server:
  port: 8080

app:
  feed:
    page-size: 20
    max-cached-feed: 1000
    celebrity-follower-threshold: 100000
    celebrity-lookback-hours: 24
  ranking:
    half-life-hours: 6
  cache:
    feed-ttl-seconds: 86400
    post-ttl-seconds: 3600

logging:
  level:
    com.systemdesign.newsfeed: DEBUG
    org.springframework.web: INFO
```
Notes: `celebrity-threshold` and `feed-size` from the original file are superseded by the nested
`app.feed.celebrity-follower-threshold` and `app.feed.max-cached-feed` that the code's `@Value`
annotations reference. `app.ranking.half-life-hours` is the NEW ranking prop.

### 11.2 `src/test/resources/application.yml` (NEW — enables graceful-degradation + fast threshold tests)

```yaml
spring:
  profiles:
    active: test
  datasource:
    url: jdbc:h2:mem:newsfeed_test
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
  data:
    redis:
      host: localhost
      port: 6370   # dead port — Redis calls must degrade gracefully in real (non-mocked) beans

app:
  feed:
    page-size: 20
    max-cached-feed: 1000
    celebrity-follower-threshold: 3   # low so FollowServiceTest can flip celebrity with few follows
    celebrity-lookback-hours: 24
  ranking:
    half-life-hours: 6
  cache:
    feed-ttl-seconds: 86400
    post-ttl-seconds: 3600
```

---

## 12. pom.xml change

The current `pom.xml` declares H2 with `<scope>test</scope>`. **Change it to `<scope>runtime</scope>`**
so H2 is on the runtime classpath (the app now boots on H2 in-memory):

```xml
<!-- H2 in-memory database (runtime datasource) -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```
Keep the PostgreSQL dependency (`<scope>runtime</scope>`) — harmless and unused at runtime.
No other pom changes required (Lombok, web, data-jpa, data-redis, validation, actuator,
springdoc, micrometer, spring-boot-starter-test remain).

---

## 13. Test Plan

Style (mirror Instagram): `@SpringBootTest` + `@ActiveProfiles("test")` with `@MockBean FeedCacheService`
and `@MockBean PostCacheService` (so Redis is not required and feed reads are deterministic); real
JPA repos on H2; `@BeforeEach` wipes tables (`postRepository/followRepository/userRepository.deleteAll()`)
and stubs `when(feedCacheService.getFeedPostIds(anyString(), anyInt(), anyInt())).thenReturn(List.of())`
and `when(postCacheService.get(anyString())).thenReturn(Optional.empty())`. Package
`com.systemdesign.newsfeed.service`.

Helper in feed/post tests to create a post (no media):
```java
private PostResponse createPost(String authorId, String content) {
    CreatePostRequest req = new CreatePostRequest();
    req.setAuthorId(authorId);
    req.setContent(content);
    return postService.createPost(req);
}
```

### 13.1 `FeedServiceTest`  (hybrid + ranking order)
Autowire: `FeedService, UserService, FollowService, PostService, FanoutService, RankingService`,
repos; `@MockBean FeedCacheService, PostCacheService`. Create `viewer`, `normalUser`, `celebrity`
(mark celebrity via `userRepository.setCelebrity(celebrity.getId(), true)`). Cases:
- `emptyFeedWhenFollowingNobody` — feed posts empty, note contains `EMPTY_FEED`.
- `coldStartFallbackWhenCacheEmpty` — cache mock returns `List.of()`; viewer follows normalUser who
  posted; feed non-empty; `feedStrategyNote` contains `fallback`.
- `feedIncludesCelebrityPostsViaReadPath` — viewer follows celebrity; celebrity posts; cache empty;
  feed contains the celebrity post (fanout-on-read).
- `feedMergesPrecomputedAndCelebrityPosts` — stub `feedCacheService.getFeedPostIds(eq(viewer.getId()), anyInt(), anyInt())`
  to return the normal post id; assert both normal + celebrity posts present, deduped.
- `feedDeduplicatesPostIds` — cache returns same id 3×; feed has size 1.
- `rankingOrdersHigherEngagementFirst` — viewer follows normalUser; create two posts A and B of
  (near-)equal age; bump B with likes/comments via `postService.likePost/commentPost`; stub cache to
  return both ids; assert `feed.getPosts().get(0)` is B (higher score) and `getScore()` of index 0 ≥ index 1.
- `feedRespectsPageSizeAndPagination` and `hasMore` flag — create >pageSize posts, stub cache to
  return their ids, assert page window size and `hasMore`.
- `feedIncludesServedAtEpochMs` (`> 0`) and `feedIncludesStrategyNote` (non-empty).

### 13.2 `FanoutServiceTest`  (celebrity skipped)
Autowire `FanoutService, PostService, UserService, FollowService`, repos; `@MockBean FeedCacheService, PostCacheService`.
- `normalUserPostFansOutToFollowers` — viewer follows normalUser; create post; fetch saved `Post`;
  call `fanoutService.fanoutSync(saved)`; `verify(feedCacheService, atLeastOnce()).addToManyFeeds(anyCollection(), eq(saved.getId()), anyLong())`.
  (Optionally, mirror Instagram's `timeout(2000).times(2)` pattern to also await the AFTER_COMMIT async event.)
- `celebrityPostDoesNotFanOut` — mark author celebrity (`userRepository.setCelebrity(..., true)`);
  create post; `fanoutService.fanoutSync(saved)`; `verify(feedCacheService, never()).addToManyFeeds(anyCollection(), anyString(), anyLong())`.
- `noFollowersIsNoOp` — author with zero followers; `fanoutSync`; `verify(..., never()).addToManyFeeds(...)`.

### 13.3 `FollowServiceTest`  (threshold flip up/down)
Autowire `FollowService, UserService`, repos; `@MockBean FeedCacheService, PostCacheService`;
`@Value("${app.feed.celebrity-follower-threshold:3}") long celebrityThreshold;`. Cases:
- `followCreatesRelationshipAndCounts`, `unfollowRemovesRelationshipAndCounts` (idempotent no-op when absent).
- `duplicateFollowThrowsIllegalState`, `selfFollowThrowsIllegalArgument`, `followNonExistentUserThrowsNoSuchElement`.
- `getFolloweeIds` / `getFollowerIds` correctness; empty lists when none.
- `crossingCelebrityThresholdSetsCelebrityFlag` — pre-increment follower count to `threshold-1`, one
  more `follow` flips `isCelebrity()` true.
- `fallingBelowCelebrityThresholdClearsCelebrityFlag` — set up at threshold + celebrity=true, then
  `unfollow` drops below → `isCelebrity()` false.
- `isCelebrityReflectsFlag`.

### 13.4 `RankingServiceTest`  (plain Mockito unit test — no Spring context)
`@ExtendWith(MockitoExtension.class)`; `@Mock PostService postService; @Mock FollowService followService;`
construct `RankingService` directly and set `halfLifeHours` (via reflection `ReflectionTestUtils.setField(ranking, "halfLifeHours", 6.0)` or a package-visible setter). Stub
`followService.getFolloweeIds(viewer)` and `postService.toResponse(any())` to echo a `PostResponse`
with matching id/authorId/likeCount/commentCount/createdAt. Build `Post` objects with explicit
`setLikeCount/ setCommentCount/ setCreatedAt` (use reflection or setters — Lombok `@Data` provides them). Cases:
- `higherEngagementRanksHigher` — two posts, equal `createdAt`, same author (or both followed);
  post with more likes+comments gets higher `score(...)` and sorts first in `rank(...)`.
- `moreRecentRanksHigher` — two posts, equal engagement & affinity; newer `createdAt` scores higher.
- `followedAuthorRanksHigherThanNonFollowed` — equal age & engagement; followed author scores higher
  (affinity 1.5 vs 1.0).
- `rankReturnsEmptyForEmptyCandidates`.
- `rankSetsScoreOnEachResponse` — every returned `PostResponse.getScore()` non-null and DESC-ordered.

### 13.5 `PostServiceTest`  (validation + counters + fanout event)
Autowire `PostService, UserService`, repos; `@MockBean FanoutService, PostCacheService, FeedCacheService`.
Cases:
- `createTextPostSucceeds` — id set, authorId/authorUsername correct, content set, `mediaUrl` null ok,
  `likeCount==0`, `commentCount==0`; author `postCount` incremented to 1.
- `createPostWithMediaUrlSucceeds` — `mediaUrl` persisted and echoed.
- `createPostIncrementsUserPostCount` — two posts → postCount 2.
- `createPostWithNonExistentAuthorThrowsNoSuchElement`.
- `createPostCachesPostResponse` — `verify(postCacheService, times(1)).put(any(PostResponse.class))`.
- `createPostTriggersFanoutEvent` — `verify(fanoutService, times(1)).onPostCreated(any())`.
- `getPostReturnsPost` / `getPostForMissingIdThrowsNoSuchElement`.
- `likePostIncrementsLikeCountAndEvictsCache` — create post; `postService.likePost(id)`;
  returned `PostResponse.getLikeCount()==1`; DB `Post.getLikeCount()==1`; `verify(postCacheService).evict(id)`.
- `commentPostIncrementsCommentCount` — analogous, `commentCount==1`.
- `getRecentPostsByAuthorsReturnsOrdered` / `filtersOldPosts` / `respectsLimit` — mirror Instagram.

---

## 14. Dependency graph (no cycles — confirm at wiring time)

```
UserController      → UserService, FollowService
PostController      → PostService
FeedController      → FeedService

UserService         → UserRepository
FollowService       → FollowRepository, UserRepository, UserService
PostService         → PostRepository, UserRepository, UserService, PostCacheService, ApplicationEventPublisher
FanoutService       → FollowService, UserService, FeedCacheService        (listens for PostCreatedEvent)
RankingService      → PostService, FollowService
FeedService         → FollowService, UserService, PostService, FeedCacheService, RankingService, PostRepository

FeedCacheService    → StringRedisTemplate, ObjectMapper
PostCacheService    → StringRedisTemplate, ObjectMapper
DataInitializer     → UserService, FollowService, PostService, UserRepository
```
All edges are acyclic. `PostService` does NOT depend on `RankingService`/`FeedService`, so
`RankingService → PostService` and `FeedService → {PostService, RankingService}` introduce no cycle.
