# Facebook News Feed Code Walkthrough

## Overview

This is a Spring Boot implementation of a Facebook-style News Feed. It demonstrates the two hardest problems in social feed systems:

1. **Hybrid fanout** — fanout-on-write for normal users (push post IDs into every follower's Redis feed) balanced against fanout-on-read for celebrities (query their recent posts at read time), so a celebrity with millions of followers never triggers millions of Redis writes.
2. **Read-time relevance ranking** — feeds are stored chronologically but re-ordered on every read by a `score = recency × engagement × affinity` formula, so the most relevant posts surface first instead of the strictly newest.

Fanout runs asynchronously on a Spring `@Async` thread pool, triggered by a `@TransactionalEventListener(AFTER_COMMIT)` so the background thread never reads an uncommitted post. Posts carry plain text `content` plus an optional single `mediaUrl` string (no separate media-upload lifecycle). Redis is used for both the per-user feed ZSET and a post-detail cache, and every Redis call degrades gracefully so the app survives a Redis outage.

> Production note: at Facebook scale fanout would be driven by a durable message queue (Kafka) with dedicated fanout workers. This implementation uses Spring `@Async` in-process for simplicity — the fanout code path is identical, only the transport differs.

## Suggested Reading Order

1. `model/User.java` — denormalized counts (followerCount, followingCount, postCount) and the `celebrity` flag that drives the fanout strategy
2. `model/Post.java` — plain `content` + optional single `mediaUrl`; `likeCount` / `commentCount` are the engagement signals ranking reads
3. `model/Follow.java` — bidirectional follow edge; UNIQUE(follower_id, followee_id) + indexes both directions
4. `event/PostCreatedEvent.java` — the event that decouples post creation from fanout
5. `service/PostService.java` — create post (save → bump count → cache → publish event), cache-aside reads, like/comment counter bumps
6. `service/FanoutService.java` — AFTER_COMMIT @Async fanout-on-write; skips celebrities
7. `cache/FeedCacheService.java` — Redis ZSET per user (`feed:{userId}`), capped at 1000, TTL 24h
8. `cache/PostCacheService.java` — Redis string cache for post detail (`post:{postId}`), TTL 1h
9. `service/FeedService.java` — THE CORE: hybrid merge of precomputed + celebrity + cold-start, then ranking, then pagination
10. `service/RankingService.java` — read-time relevance scoring (recency × engagement × affinity)
11. `service/FollowService.java` — follow graph + counter maintenance + atomic celebrity flag flip
12. `service/UserService.java` — user CRUD + DTO mapping
13. `controller/PostController.java` — create/get post, like, comment
14. `controller/FeedController.java` — get feed (paginated, page-size guarded)
15. `controller/UserController.java` — user CRUD + follow/unfollow + follower/following lists
16. `controller/GlobalExceptionHandler.java` — exception → HTTP status mapping
17. `config/AsyncConfig.java` — fanoutExecutor thread pool (10–50 threads, 1000 queue, CallerRunsPolicy)
18. `config/RedisConfig.java` — StringRedisTemplate + ObjectMapper beans
19. `config/DataInitializer.java` — demo data seeding (`@Profile("!test")`)

---

## Package Map

```
com.systemdesign.newsfeed/
├── NewsFeedApplication.java        — Spring Boot entry point (@EnableCaching, @EnableAsync)
├── model/
│   ├── User.java                   — JPA entity; denormalized followerCount/followingCount/postCount + celebrity flag
│   ├── Post.java                   — JPA entity; authorId, content, optional mediaUrl, likeCount, commentCount
│   └── Follow.java                 — JPA entity; UNIQUE(follower_id, followee_id); indexes both directions
├── event/
│   └── PostCreatedEvent.java       — ApplicationEvent carrying the committed Post; triggers async fanout
├── dto/
│   ├── CreateUserRequest.java      — username, displayName, email, bio
│   ├── UserResponse.java           — user details + counts + celebrity flag
│   ├── CreatePostRequest.java      — authorId, content, mediaUrl
│   ├── PostResponse.java           — post details + authorUsername + nullable ranking score
│   └── FeedResponse.java           — userId, posts, page, pageSize, hasMore, servedAtEpochMs, feedStrategyNote
├── repository/
│   ├── UserRepository.java         — findByUsername; atomic incrementFollower/Following/PostCount; setCelebrity + conditional celebrity flips; getFollowerCount
│   ├── PostRepository.java         — findByAuthorId(...OrderByCreatedAtDesc); findByAuthorIdIn...; findByAuthorIdInAndCreatedAtAfter...; atomic incrementLike/CommentCount
│   └── FollowRepository.java       — findBy/existsBy/deleteByFollowerIdAndFolloweeId; findFolloweeIds; findFollowerIds; countByFolloweeId
├── service/
│   ├── UserService.java            — create/get user; entity→DTO mapping
│   ├── PostService.java            — createPost (save+count+cache+event), getPost (cache-aside), like/commentPost, getRecentPostsByAuthors, toResponse
│   ├── FanoutService.java          — @Async AFTER_COMMIT fanout-on-write; skips celebrities
│   ├── FeedService.java            — HYBRID MERGE (precomputed + celebrity + cold-start) → RANK → paginate
│   ├── RankingService.java         — read-time relevance: recency × engagement × affinity
│   └── FollowService.java          — follow/unfollow + maintain counts + atomic celebrity flag flip
├── cache/
│   ├── FeedCacheService.java       — Redis ZSET: feed:{userId}, member=postId, score=createdAt epochMs; capped 1000, TTL 24h
│   └── PostCacheService.java       — Redis string: post:{postId}, value=PostResponse JSON; TTL 1h
├── controller/
│   ├── UserController.java         — POST /api/users, GET /api/users/{id}, POST/DELETE /api/users/{id}/follow, GET followers/following
│   ├── PostController.java         — POST /api/posts, GET /api/posts/{id}, POST /api/posts/{id}/like, POST /api/posts/{id}/comment
│   ├── FeedController.java         — GET /api/feed?userId=X&page=0&pageSize=20
│   └── GlobalExceptionHandler.java — NoSuchElementException → 404, IllegalStateException → 409, IllegalArgumentException → 400
└── config/
    ├── RedisConfig.java            — StringRedisTemplate + ObjectMapper (JavaTimeModule) beans
    ├── AsyncConfig.java            — fanoutExecutor (core=10, max=50, queue=1000, CallerRunsPolicy) + async exception handler
    └── DataInitializer.java        — seeds 5 users, follows, 8 posts, engagement (@Profile("!test"))
```

---

## Key Flow Traces

### WRITE PATH: Create Post → Event → Async Fanout (AFTER_COMMIT) → Redis ZSETs

```
CREATE POST (POST /api/posts)

PostController.createPost(req)          // req = { authorId, content, mediaUrl? }
  ├── Validate authorId non-blank  → 400 IllegalArgumentException if missing
  ├── Validate content non-blank   → 400 IllegalArgumentException if missing
  └── PostService.createPost(req)       // @Transactional

PostService.createPost(req)
  ├── userService.getUser(authorId)                → 404 NoSuchElementException if author missing
  ├── Build Post entity (authorId, content, mediaUrl; counters default 0)
  ├── postRepository.save(post)                    → auto-generated UUID id, createdAt via @PrePersist
  ├── userRepository.incrementPostCount(authorId, 1)   → atomic UPDATE
  ├── response = toResponse(saved)                 → resolves authorUsername from UserRepository
  ├── postCacheService.put(response)               → Redis SET post:{postId} (JSON, TTL 1h)
  └── eventPublisher.publishEvent(new PostCreatedEvent(this, saved))   → returns immediately (201)

  // Fanout runs AFTER the createPost transaction commits, on a background thread:
  FanoutService.onPostCreated(event)    // @TransactionalEventListener(AFTER_COMMIT) + @Async("fanoutExecutor")
    └── fanoutSync(event.getPost()):
          ├── followService.isCelebrity(authorId) → if true, LOG & RETURN (skip fanout; handled on read)
          ├── followService.getFollowerIds(authorId) → ["user1", "user2", ...]; if empty, RETURN
          ├── scoreMs = post.createdAt.toInstant(UTC).toEpochMilli()
          └── feedCacheService.addToManyFeeds(followerIds, postId, scoreMs):
                → FOR each followerId:
                    ZADD feed:{followerId} {scoreMs} {postId}
                    if ZCARD > 1000: ZREMRANGEBYRANK feed:{followerId} 0 (size-1000-1)   // trim to newest 1000
                    EXPIRE feed:{followerId} 86400s

→ Post is now in every (non-celebrity author's) follower's Redis feed ZSET.
```

Engagement writes (`POST /api/posts/{id}/like`, `/comment`) follow a smaller path: `PostService.likePost` / `commentPost` verify existence with `existsById`, run an atomic `incrementLikeCount` / `incrementCommentCount`, `postCacheService.evict(postId)`, then re-read via `getPost` (which re-populates the cache). These counters feed the ranking engagement signal.

### READ PATH: Get Feed (Hybrid Merge → Rank → Paginate)

```
GET /api/feed?userId=alice&page=0&pageSize=20

FeedController.getFeed(userId, page, pageSize)
  ├── Validate page >= 0, pageSize in (0, 100]  → 400 IllegalArgumentException otherwise
  └── FeedService.getFeed(userId, page, pageSize)

FeedService.getFeed(userId, page, pageSize)
  ├── userService.getUser(userId)                         → 404 if viewer missing
  ├── 1. followService.getFolloweeIds(userId)             → whom does viewer follow?
  │       → if empty: return buildEmptyFeed(...) ("EMPTY_FEED (user follows nobody)")
  │
  ├── 2. FANOUT-ON-WRITE (precomputed):
  │       feedCacheService.getFeedPostIds(userId, 0, maxCachedFeed=1000)
  │         → ZREVRANGE feed:{userId} 0 999   (newest first; [] on cache miss / Redis down)
  │
  ├── 3. FANOUT-ON-READ (celebrity posts):
  │       ├── celebrityFolloweeIds = followeeIds.filter(followService::isCelebrity)
  │       ├── celebrityCutoff = now - celebrityLookbackHours(24h)
  │       └── postService.getRecentPostsByAuthors(celebrityFolloweeIds, celebrityCutoff, pageSize*3+1)
  │             → PostRepository.findByAuthorIdInAndCreatedAtAfterOrderByCreatedAtDesc(...)
  │
  ├── 4. COLD-START FALLBACK:
  │       coldStart = precomputedPostIds.isEmpty()
  │       if coldStart:
  │         postRepository.findByAuthorIdInOrderByCreatedAtDesc(followeeIds, PageRequest.of(0, pageSize*3))
  │
  ├── 5. MERGE + DEDUP  (LinkedHashMap<String, Post> keyed by postId):
  │       ├── put all fallbackPosts
  │       ├── put all celebrityPosts (overwrites duplicates)
  │       └── for each precomputed postId not already in map: postRepository.findById → put (try/catch)
  │
  ├── 6. RANK:  rankingService.rank(userId, new ArrayList<>(postMap.values()))
  │       → scores every candidate for this viewer and returns PostResponse list, score DESC
  │
  ├── 7. PAGINATE the ranked list:
  │       startIdx = page * pageSize;  endIdx = min(startIdx + pageSize, ranked.size())
  │       pagePosts = (startIdx < ranked.size()) ? ranked.subList(startIdx, endIdx) : []
  │
  └── 8. BUILD RESPONSE:
          FeedResponse {
            userId, posts=pagePosts, page, pageSize,
            hasMore = ranked.size() > startIdx + pageSize,
            servedAtEpochMs,
            feedStrategyNote: e.g. "FANOUT_ON_WRITE (precomputed: 45 post IDs from Redis)
                               + FANOUT_ON_READ (celebrity: 3 posts from 1 celebrities, last 24h)
                               + RANKED (relevance: recency×engagement×affinity)"
          }
```

### RANKING (step 6 detail)

```
RankingService.rank(viewerId, candidates)
  ├── if candidates empty: return []
  ├── followeeIds = new HashSet<>(followService.getFolloweeIds(viewerId))   // one affinity lookup for the batch
  ├── FOR each post:
  │     s = score(viewerId, post, followeeIds)
  │     r = postService.toResponse(post);  r.setScore(s);  ranked.add(r)
  └── sort by score DESC, tie-break createdAt DESC (ISO string compare)

RankingService.score(viewerId, post, followeeIds)
  ├── ageHours       = max(0, millisBetween(post.createdAt, now) / 3_600_000)
  ├── recencyDecay   = exp(-ageHours / halfLifeHours(6))                      // (0, 1], newer → higher
  ├── engagementBoost = 1 + log1p(likeCount + 2 * commentCount)              // comments weighted 2×, >= 1
  ├── affinity       = followeeIds.contains(post.authorId) ? 1.5 : 1.0
  └── return recencyDecay * engagementBoost * affinity
```

---

## Why Key Decisions Were Made in Code

### Why hybrid fanout (write for normal users, read for celebrities)

`FanoutService.fanoutSync()` pushes a post into every follower's Redis feed — but only for non-celebrity authors:

```java
if (followService.isCelebrity(post.getAuthorId())) {
    log.info("Skipping fanout for celebrity post id={} authorId={} (fanout-on-read)", ...);
    return;
}
```

Fanout-on-write makes reads cheap (a single `ZREVRANGE`) but writes proportional to follower count. For normal users that is fine. For a celebrity with millions of followers it would mean millions of `ZADD` calls per post — unsustainable write amplification. So celebrities are handled on the read side instead: `FeedService.getFeed()` filters followees to celebrities and pulls their recent posts directly from the DB on every read:

```java
List<String> celebrityFolloweeIds = followeeIds.stream()
    .filter(followService::isCelebrity)
    .collect(Collectors.toList());
// ...
postService.getRecentPostsByAuthors(celebrityFolloweeIds, celebrityCutoff, pageSize * 3 + 1);
```

This trades cheap write amplification for a bounded read-time query (indexed on `author_id` + `created_at`).

### Why the feed ZSET is capped at 1000 posts

`FeedCacheService.addToFeed()` trims after every `ZADD`:

```java
long feedSize = redis.opsForZSet().zCard(key);
if (feedSize > maxCachedFeed) {                            // maxCachedFeed = 1000
    redis.opsForZSet().removeRange(key, 0, feedSize - maxCachedFeed - 1);
}
```

Feeds grow without bound otherwise, and at hundreds of millions of users the Redis memory footprint would explode. Users almost never scroll past the most recent ~1000 posts; anything older is recoverable from the DB via the cold-start fallback path.

### Why read-time ranking instead of pure chronological order

Fanout stores post IDs by timestamp, but the feed is *not* returned in that order. `FeedService` hands the merged candidate set to `RankingService.rank()`, which scores each post for the viewer:

```java
return recencyDecay * engagementBoost * affinity;
```

- **recencyDecay** = `exp(-ageHours / halfLifeHours)` keeps fresh content on top without a hard cutoff.
- **engagementBoost** = `1 + log1p(likeCount + 2*commentCount)` surfaces popular posts; `log1p` gives diminishing returns so a viral post cannot dominate, and comments count double as a stronger engagement signal than likes.
- **affinity** = `1.5` when the viewer follows the author, else `1.0`, nudging content from directly-followed accounts above incidental candidates.

All three factors multiply, so a post must be reasonably recent *and* engaging *and* relevant to rank at the top — mirroring how real feeds beat reverse-chronological ordering. Ties break on `createdAt` DESC for deterministic output.

### Why the celebrity flag flips atomically at the follower threshold

`FollowService.follow()` promotes on the way up using a single conditional UPDATE:

```java
int updated = userRepository.setCelebrityIfCountAboveThreshold(
    followeeId, celebrityFollowerThreshold, true);
```

backed by:

```java
@Query("UPDATE User u SET u.celebrity = :flag WHERE u.id = :id AND u.followerCount >= :threshold AND u.celebrity != :flag")
int setCelebrityIfCountAboveThreshold(...);
```

`unfollow()` mirrors it with `setCelebrityIfCountBelowThreshold` (`followerCount < threshold`). Doing the compare-and-set inside one atomic UPDATE (rather than read-count-then-set) avoids a race where two concurrent follows both read a stale count and disagree on the flag. The `celebrity != :flag` guard makes it idempotent and lets the caller know (rows-updated > 0) whether the status actually changed. The moment a user crosses the threshold (100000 in prod, 3 in tests), their posts stop being fanned out on write — exactly the case hybrid fanout exists to protect.

### Why cold-start fallback exists

`FeedService.getFeed()` treats an empty precomputed feed as a cold start:

```java
boolean coldStart = precomputedPostIds.isEmpty();
if (coldStart) {
    fallbackPosts = postRepository.findByAuthorIdInOrderByCreatedAtDesc(
        followeeIds, PageRequest.of(0, pageSize * 3));
}
```

The Redis feed can be empty for several reasons: a brand-new user with nothing fanned out yet, a cache eviction / TTL expiry, a Redis outage, or a viewer who only follows celebrities (never fanned out on write). Without the fallback those users would see an empty feed even though their followees have recent posts. The DB query guarantees a usable feed on cache miss.

### Why every Redis operation degrades gracefully

Both cache services wrap every Redis call in try/catch and return a safe default:

```java
public List<String> getFeedPostIds(String userId, int offset, int limit) {
    try {
        ...
        return new ArrayList<>(postIds);
    } catch (Exception e) {
        log.warn("Feed cache GET failed userId={}: {}", userId, e.getMessage());
        return new ArrayList<>();   // treated as cold start → DB fallback
    }
}
```

If Redis is down, feed reads return `[]` (triggering the cold-start DB path) and fanout writes are no-ops. The app stays functional — Redis is a performance layer, not a single point of failure. The p99 latency target may slip during an outage, but requests still succeed. `DataInitializer` is likewise wrapped in try/catch so the app boots even without Redis.

### Why fanout is event-driven and runs AFTER_COMMIT on @Async

`PostService.createPost()` does not call fanout directly — it publishes a `PostCreatedEvent`. `FanoutService.onPostCreated()` consumes it with both annotations:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async("fanoutExecutor")
public void onPostCreated(PostCreatedEvent event) {
    fanoutSync(event.getPost());
}
```

- **AFTER_COMMIT**: a plain `@Async` call fired from inside `createPost()` could run on a background thread that queries the DB *before* the post transaction committed — a read-before-commit race where the fanout thread sees no post or a stale celebrity flag. Binding to `AFTER_COMMIT` guarantees the post and the incremented counters are durably committed and visible first.
- **@Async("fanoutExecutor")**: fanout to N followers is N sequential Redis writes. Running it inline would block the HTTP response for the whole loop. On the async pool, `createPost` returns `201` immediately and fanout proceeds in the background.

`fanoutSync` is also exposed as a plain public method so tests can invoke the fanout logic deterministically without waiting on the async pool (see `FanoutServiceTest`).

### Why the fanout executor uses CallerRunsPolicy

`AsyncConfig.fanoutExecutor()` is a bounded pool (core 10, max 50, queue 1000) with:

```java
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
```

When the queue saturates under load, new fanout tasks run on the calling thread instead of throwing `RejectedExecutionException`. This applies natural backpressure (post creation slows down) rather than dropping fanout work. An `AsyncUncaughtExceptionHandler` logs any uncaught async failure with the method name for alerting.

### Why like/comment use existsById + evict + re-read

`PostService.likePost()` deliberately checks existence with `existsById` rather than loading the entity:

```java
if (!postRepository.existsById(postId)) {
    throw new NoSuchElementException("Post not found: " + postId);
}
postRepository.incrementLikeCount(postId, 1);   // bulk @Modifying UPDATE
postCacheService.evict(postId);                 // drop stale cached JSON
return getPost(postId);                          // re-read + re-cache fresh count
```

A bulk `@Modifying` UPDATE does not refresh managed entities, so a prior `findById` would leave a stale copy in the persistence-context L1 cache and the re-read would return the old count. `existsById` keeps the L1 cache clean so `getPost` observes the just-written value. Evicting the Redis entry ensures the next reader does not serve a stale like/comment count.

---

## Dependency Flow

```
FeedController
    └── FeedService
            ├── UserService        (getUser — validate viewer)
            ├── FollowService      (getFolloweeIds, isCelebrity)
            ├── PostService        (getRecentPostsByAuthors, toResponse)
            ├── FeedCacheService   (getFeedPostIds — Redis ZREVRANGE)
            ├── RankingService     (rank) ──┐
            └── PostRepository     (findByAuthorIdIn... — celebrity + cold-start)
                                            │
RankingService ─────────────────────────────┘
    ├── FollowService  (getFolloweeIds — affinity)
    └── PostService    (toResponse)

PostController
    └── PostService
            ├── UserService              (getUser — validate author)
            ├── PostRepository           (save, findById, existsById, incrementLike/CommentCount)
            ├── UserRepository           (incrementPostCount)
            ├── PostCacheService         (put, get, evict)
            └── ApplicationEventPublisher (PostCreatedEvent)
                        │
                        └── FanoutService  (@Async AFTER_COMMIT)
                                ├── FollowService     (isCelebrity, getFollowerIds)
                                ├── UserService       (injected)
                                └── FeedCacheService  (addToManyFeeds — Redis ZADD)

UserController
    ├── UserService     (createUser, getUserResponse)
    └── FollowService
            ├── FollowRepository (save, delete, exists, findFolloweeIds, findFollowerIds)
            ├── UserRepository   (incrementFollower/FollowingCount, setCelebrityIfCount..., getFollowerCount)
            └── UserService      (getUser — validate both users)
```

---

## Key Invariants

1. **Celebrity posts are never fanned out** — enforced by `FanoutService.fanoutSync()` returning early when the author is a celebrity; those posts reach feeds only via `FeedService`'s fanout-on-read path.
2. **Feed ZSET is capped at 1000 posts** — enforced by `FeedCacheService.addToFeed()` trimming with `removeRange` (ZREMRANGEBYRANK) after every `ZADD`.
3. **Feeds are ranked, not chronological** — `FeedService` always routes the merged candidate set through `RankingService.rank()`; every returned `PostResponse` carries a viewer-specific `score`.
4. **Celebrity flag tracks the follower threshold** — enforced by `FollowService.follow()` / `unfollow()` via the atomic conditional updates `setCelebrityIfCountAboveThreshold` / `setCelebrityIfCountBelowThreshold`.
5. **Denormalized counts stay accurate** — maintained by atomic `incrementFollowerCount`, `incrementFollowingCount`, `incrementPostCount`, `incrementLikeCount`, `incrementCommentCount` (`@Modifying` UPDATEs).
6. **Redis failures degrade gracefully** — every cache op is wrapped in try/catch; feed reads fall back to the cold-start DB query and fanout writes become no-ops.
7. **Follow relationship (followerId, followeeId) is unique** — enforced by `@UniqueConstraint` on the `Follow` entity plus a service-level `existsByFollowerIdAndFolloweeId` check; self-follows are rejected.
8. **Fanout runs after commit, off the request thread** — enforced by `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("fanoutExecutor")`, so it never reads uncommitted data and never blocks the create-post response.
9. **Cached post detail is never stale after engagement** — `likePost` / `commentPost` `evict` the `post:{postId}` entry after each counter bump, and `existsById` (not `findById`) keeps the JPA L1 cache clean so the re-read returns the fresh count.
