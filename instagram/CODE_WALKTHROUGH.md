# Instagram Code Walkthrough

## Overview

This is a Spring Boot implementation of an Instagram-like photo/video sharing platform. It demonstrates the core challenge in social feed systems: **hybrid fanout** (balancing fanout-on-write for normal users with fanout-on-read for celebrities) and **presigned upload URLs** (client-side media uploads without proxying through the app server).

## Suggested Reading Order

1. `model/User.java` — notice denormalized counts (followerCount, followingCount, postCount) and the `celebrity` flag
2. `model/Media.java` — media lifecycle: PENDING → UPLOADED; notice `uploadUrl` and `cdnUrl` separation
3. `model/Post.java` — notice `mediaUrls` list (CDN URLs, not media IDs) — posts reference completed uploads
4. `model/Follow.java` — bidirectional index for follower/followee lookups
5. `service/MediaService.java` — presigned upload URL generation + completion validation
6. `service/PostService.java` — post creation pipeline: validate media → save → increment counts → cache → trigger fanout
7. `service/FanoutService.java` — async fanout-on-write; skips celebrities
8. `service/FeedService.java` — THE CORE: hybrid merge of precomputed + celebrity + fallback feeds
9. `cache/FeedCacheService.java` — Redis ZSET per user (insta:feed:{userId}), capped at 1000 posts
10. `cache/PostCacheService.java` — Redis cache for individual posts (cache-aside pattern)
11. `service/FollowService.java` — follow graph maintenance + celebrity threshold enforcement
12. `controller/MediaController.java` — upload URL request + completion endpoints
13. `controller/PostController.java` — create post + get post
14. `controller/FeedController.java` — get feed (paginated)
15. `controller/UserController.java` — user CRUD + follow/unfollow
16. `config/AsyncConfig.java` — fanoutExecutor thread pool (10-50 threads, 1000 queue)
17. `config/DataInitializer.java` — demo data seeding (@Profile("!test"))

---

## Package Map

```
com.systemdesign.instagram/
├── InstagramApplication.java       — Spring Boot entry point
├── model/
│   ├── User.java                   — JPA entity with denormalized followerCount, followingCount, postCount, celebrity flag
│   ├── Media.java                  — JPA entity; status: PENDING → UPLOADED; has uploadUrl + cdnUrl
│   ├── MediaType.java              — enum: PHOTO, VIDEO, CAROUSEL
│   ├── MediaStatus.java            — enum: PENDING, UPLOADED
│   ├── Post.java                   — JPA entity; stores mediaUrls (CDN URLs), not media IDs
│   └── Follow.java                 — JPA entity; UNIQUE(followerId, followeeId); indexes for both directions
├── dto/
│   ├── CreateUserRequest.java      — username, displayName, email, bio
│   ├── UserResponse.java           — user details + counts + celebrity status
│   ├── UploadUrlRequest.java       — uploaderId, type, sizeBytes
│   ├── UploadUrlResponse.java      — mediaId, uploadUrl, cdnUrl, blobKey, maxSizeBytes
│   ├── CompleteUploadRequest.java  — (not used; mediaId is path param)
│   ├── CreatePostRequest.java      — authorId, caption, mediaType, mediaIds (list)
│   ├── PostResponse.java           — post details + authorUsername + mediaUrls (CDN)
│   └── FeedResponse.java           — posts, page, pageSize, hasMore, feedStrategyNote
├── repository/
│   ├── UserRepository.java         — incrementFollowerCount, incrementFollowingCount, incrementPostCount, setCelebrity, getFollowerCount
│   ├── MediaRepository.java        — standard JpaRepository
│   ├── PostRepository.java         — findByAuthorIdInOrderByCreatedAtDesc, findByAuthorIdInAndCreatedAtAfterOrderByCreatedAtDesc
│   └── FollowRepository.java       — findFolloweeIds, findFollowerIds, deleteByFollowerIdAndFolloweeId
├── service/
│   ├── UserService.java            — user CRUD
│   ├── MediaService.java           — createUploadUrl, completeUpload, resolveCdnUrls (validates UPLOADED status)
│   ├── PostService.java            — createPost (validate + save + cache + fanout), getPost (cache-aside)
│   ├── FanoutService.java          — @Async fanout; skips celebrities
│   ├── FeedService.java            — HYBRID MERGE: precomputed Redis + celebrity DB pull + cold-start fallback
│   └── FollowService.java          — follow/unfollow + maintain counts + flip celebrity flag at threshold
├── cache/
│   ├── FeedCacheService.java       — Redis ZSET: insta:feed:{userId}, member=postId, score=createdAt epochMs; capped at 1000
│   └── PostCacheService.java       — Redis string: insta:post:{postId}, value=PostResponse JSON; TTL 1h
├── controller/
│   ├── UserController.java         — POST /api/users, GET /api/users/{userId}, POST /api/users/{userId}/follow, DELETE /api/users/{userId}/follow
│   ├── MediaController.java        — POST /api/media/upload-url, POST /api/media/{mediaId}/complete
│   ├── PostController.java         — POST /api/posts, GET /api/posts/{postId}
│   ├── FeedController.java         — GET /api/feed?userId=X&page=0&pageSize=20
│   └── GlobalExceptionHandler.java — NoSuchElementException → 404, IllegalStateException → 409, IllegalArgumentException → 400
└── config/
    ├── RedisConfig.java            — StringRedisTemplate + ObjectMapper beans
    ├── AsyncConfig.java            — fanoutExecutor thread pool (core=10, max=50, queue=1000)
    └── DataInitializer.java        — Seeds demo users, follows, media, posts
```

---

## Key Flow Traces

### WRITE PATH: Upload Media → Create Post → Fanout

```
1. REQUEST UPLOAD URL (POST /api/media/upload-url)

MediaController.createUploadUrl()

MediaService.createUploadUrl(req)
  ├── Validate: sizeBytes <= maxPhotoBytes (8MB) or maxVideoBytes (4GB)
  ├── Create Media entity: status=PENDING, uploaderId, type, sizeBytes
  ├── Save to DB → get auto-generated mediaId
  ├── Generate blobKey: {uploaderId}/{mediaId}
  ├── Generate uploadUrl: https://uploads.../user123/media456?sig=SIMULATED
  ├── Generate cdnUrl: https://cdn.../user123/media456
  ├── Update media with blobKey, uploadUrl, cdnUrl
  └── Return UploadUrlResponse

→ Client uploads file to uploadUrl (simulated; in production this would be S3/GCS presigned URL)


2. MARK UPLOAD COMPLETE (POST /api/media/{mediaId}/complete)

MediaController.completeUpload(mediaId)

MediaService.completeUpload(mediaId)
  ├── Fetch media by ID → 404 if not found
  ├── Update: status=UPLOADED
  └── Save


3. CREATE POST (POST /api/posts)

PostController.createPost(req)
  // req = { authorId, caption, mediaType, mediaIds: ["media456", "media789"] }

PostService.createPost(req)
  ├── userService.getUser(authorId) → 404 if not found
  ├── mediaService.resolveCdnUrls(mediaIds):
  │     → FOR each mediaId:
  │         fetch Media entity → 404 if not found
  │         check status == UPLOADED → 409 "Media not uploaded" if PENDING
  │         collect cdnUrl
  │     → return list of CDN URLs
  ├── Build Post entity:
  │     authorId, caption, mediaType, mediaUrls=[CDN URLs]
  ├── postRepository.save(post)
  ├── userRepository.incrementPostCount(authorId, +1)
  ├── postCacheService.put(postResponse) → Redis SET insta:post:{postId}
  └── eventPublisher.publishEvent(new PostCreatedEvent(this, post)) → returns immediately

  // Fanout runs AFTER the createPost transaction commits, on a background thread:
  FanoutService.onPostCreated(event)   // @TransactionalEventListener(AFTER_COMMIT) + @Async("fanoutExecutor")
    └── fanoutService.fanoutSync(event.getPost()):
          ├── Check: followService.isCelebrity(authorId) → if true, SKIP fanout (log & return)
          ├── followService.getFollowerIds(authorId) → ["user1", "user2", ...]
          ├── Convert post.createdAt → epoch millis (score)
          └── feedCacheService.addToManyFeeds(followerIds, postId, scoreMs):
                → FOR each followerId:
                    ZADD insta:feed:{followerId} {scoreMs} {postId}
                    ZREMRANGEBYRANK ... (trim to keep newest 1000 posts)
                    EXPIRE insta:feed:{followerId} 86400s

→ Post is now fanned out to all followers' Redis feeds (if author is not celebrity)
```

### READ PATH: Get Feed (Hybrid Merge)

```
GET /api/feed?userId=alice&page=0&pageSize=20

FeedController.getFeed(userId, page, pageSize)

FeedService.getFeed(userId, page, pageSize)
  ├── 1. followService.getFolloweeIds(userId) → ["bob", "charlie", "celeb1"]
  │       → if empty: return empty feed
  │
  ├── 2. FANOUT-ON-WRITE (precomputed):
  │       feedCacheService.getFeedPostIds(userId, offset=0, limit=60)
  │         → ZREVRANGE insta:feed:{userId} 0 59
  │         → returns ["post999", "post888", ...] (newest first)
  │
  ├── 3. FANOUT-ON-READ (celebrity posts):
  │       ├── Filter followeeIds → celebrityFolloweeIds (where user.celebrity == true)
  │       ├── Calculate celebrityCutoff = now - celebrityLookbackHours (24h)
  │       └── postService.getRecentPostsByAuthors(celebrityFolloweeIds, celebrityCutoff, pageSize*3)
  │             → postRepository.findByAuthorIdInAndCreatedAtAfterOrderByCreatedAtDesc(...)
  │             → SQL: WHERE author_id IN (...) AND created_at > ? ORDER BY created_at DESC LIMIT 60
  │
  ├── 4. COLD-START FALLBACK:
  │       ├── if precomputedPostIds.isEmpty():
  │       │     postRepository.findByAuthorIdInOrderByCreatedAtDesc(followeeIds, pageable)
  │       │       → SQL: WHERE author_id IN (...) ORDER BY created_at DESC LIMIT 60
  │       │       → this happens if Redis feed is empty (new user or cache eviction)
  │
  ├── 5. MERGE:
  │       Map<String, Post> postMap = new LinkedHashMap<>()
  │       ├── Add fallback posts (if cold start)
  │       ├── Add celebrity posts (overwrite if duplicate)
  │       └── Hydrate precomputed post IDs from DB (skip if already in map)
  │             → FOR each postId: postRepository.findById(postId)
  │
  ├── 6. SORT:
  │       allPosts = postMap.values().stream()
  │         .sorted(Comparator.comparing(Post::getCreatedAt).reversed())
  │
  ├── 7. PAGINATE:
  │       startIdx = page * pageSize (e.g., 0 * 20 = 0)
  │       endIdx = min(startIdx + pageSize, allPosts.size())
  │       pagePosts = allPosts.subList(startIdx, endIdx)
  │
  ├── 8. HYDRATE TO DTO:
  │       postResponses = pagePosts.stream().map(postService::toResponse)
  │         → PostService.toResponse(post):
  │               resolve authorUsername from UserRepository (fallback to authorId)
  │
  └── 9. BUILD RESPONSE:
          FeedResponse {
            userId, posts, page, pageSize, hasMore,
            feedStrategyNote: "FANOUT_ON_WRITE (precomputed: 45 post IDs from Redis) + FANOUT_ON_READ (celebrity: 3 posts from 1 celebrities, last 24h)",
            servedAtEpochMs
          }
```

---

## Why Key Decisions Were Made in Code

### Why posts store `mediaUrls` (CDN URLs), not `mediaIds`

`PostService.createPost()` calls `mediaService.resolveCdnUrls(mediaIds)` which:
```java
for (String mediaId : mediaIds) {
    Media media = getMedia(mediaId);
    if (media.getStatus() != MediaStatus.UPLOADED) {
        throw new IllegalStateException("Media not uploaded: " + mediaId);
    }
    cdnUrls.add(media.getCdnUrl());
}
```

This enforces the invariant: **posts reference only UPLOADED media**. The CDN URL is resolved once at post creation time and stored directly. This avoids a JOIN to the `media` table on every feed read.

Why not store media IDs and resolve URLs at read time? At 500M DAU and 100M posts/day, the feed query would need to JOIN posts → media for every post in the feed. Storing the CDN URL makes feed reads a simple SELECT from the posts table.

### Why celebrity posts are never fanned out

`FanoutService.fanoutSync()` checks:
```java
boolean isCelebrity = followService.isCelebrity(post.getAuthorId());
if (isCelebrity) {
    log.info("Skipping fanout for celebrity post...");
    return;
}
```

If a celebrity with 10M followers posts, fanout-on-write would execute 10M Redis writes (ZADD). At 100M posts/day, that's unsustainable write amplification.

Instead, `FeedService.getFeed()` queries recent celebrity posts on every read:
```java
List<String> celebrityFolloweeIds = followeeIds.stream()
    .filter(followService::isCelebrity)
    .collect(Collectors.toList());
// ...
postService.getRecentPostsByAuthors(celebrityFolloweeIds, celebrityCutoff, limit)
```

This trades write amplification (cheap) for read latency (acceptable, given DB index on `author_id` + `created_at`).

### Why the feed ZSET is capped at 1000 posts

`FeedCacheService.addToFeed()` trims the ZSET:
```java
long feedSize = redis.opsForZSet().zCard(key);
if (feedSize > maxCachedFeed) {
    redis.opsForZSet().removeRange(key, 0, feedSize - maxCachedFeed - 1);
}
```

At 500M DAU, if each user's feed grew unbounded, the Redis memory footprint would be:
- 500M users × avg 10,000 posts × 32 bytes (postId + score) = 160 TB

Capping at 1000 posts per user:
- 500M × 1000 × 32 = 16 TB (10× reduction)

Users rarely scroll past the first 1000 posts in their feed. Older posts fall back to the DB query.

### Why celebrity threshold flips the flag immediately

`FollowService.follow()` checks the follower count after every follow:
```java
userRepository.incrementFollowerCount(followeeId, 1);
Long followerCount = userRepository.getFollowerCount(followeeId);
if (followerCount >= celebrityFollowerThreshold) {
    userRepository.setCelebrity(followeeId, true);
}
```

This ensures the fanout strategy switches as soon as the threshold is crossed. Without this, a user who just crossed 100K followers would still have posts fanned out to 100K+ Redis feeds — the exact scenario hybrid fanout was designed to avoid.

The `unfollow()` logic mirrors this:
```java
if (followerCount < celebrityFollowerThreshold) {
    userRepository.setCelebrity(followeeId, false);
}
```

### Why feed cache uses epoch milliseconds as ZSET score

`FanoutService.fanoutSync()` converts the post timestamp:
```java
long scoreMs = post.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
feedCacheService.addToManyFeeds(followerIds, post.getId(), scoreMs);
```

Redis ZSET scores are 64-bit doubles. Using epoch milliseconds (e.g., 1720000000000) allows reverse-chronological sorting:
```java
redis.opsForZSet().reverseRange(key, offset, offset + limit - 1);
```

This returns the newest posts first (highest score = most recent).

Why not use Unix seconds? Millisecond precision handles the edge case where two posts are created in the same second — the millisecond timestamp ensures correct ordering.

### Why cold-start fallback exists

`FeedService.getFeed()` checks if the precomputed feed is empty:
```java
boolean coldStart = precomputedPostIds.isEmpty();
if (coldStart) {
    fallbackPosts = postRepository.findByAuthorIdInOrderByCreatedAtDesc(followeeIds, pageable);
}
```

The Redis feed can be empty for three reasons:
1. **New user** — no posts have been fanned out yet
2. **Cache eviction** — Redis TTL expired or memory pressure evicted the key
3. **Celebrity-only follows** — user only follows celebrities, so no fanout-on-write happened

Without the fallback, these users would see an empty feed even if their followees have recent posts. The DB query ensures a consistent feed experience even on cache miss.

### Why Redis failures degrade gracefully

Every Redis operation is wrapped in try/catch:
```java
try {
    redis.opsForZSet().add(key, postId, scoreMs);
} catch (Exception e) {
    log.warn("Feed cache ADD failed userId={} postId={}: {}", userId, postId, e.getMessage());
}
```

If Redis goes down, the app stays alive:
- Feed reads fall back to cold-start DB query
- Post writes skip fanout (celebrity-like behavior for all users temporarily)

This prevents Redis from being a single point of failure. The NFR target (p99 < 500ms) may not be met during Redis downtime, but the app remains functional.

### Why fanout is event-driven and @Async

`PostService.createPost()` does not call fanout directly. Instead it publishes a `PostCreatedEvent`, and `FanoutService.onPostCreated()` handles it with `@TransactionalEventListener(phase = AFTER_COMMIT)` plus `@Async("fanoutExecutor")`:
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async("fanoutExecutor")
public void onPostCreated(PostCreatedEvent event) {
    fanoutSync(event.getPost());
}
```

**Why `AFTER_COMMIT`?** A plain `@Async` call fired from inside `createPost()` would run on a background thread that could read the database *before* the post transaction committed — a read-before-commit race where the fanout thread sees no post (or a stale celebrity flag). Binding fanout to the `AFTER_COMMIT` phase guarantees the post and all counter updates are durably committed and visible before fanout starts.

Without async, `PostService.createPost()` would block on the fanout loop:
```java
for (String userId : followerIds) {
    addToFeed(userId, postId, scoreMs);  // Redis ZADD
}
```

At 1,000 followers, this is 1,000 sequential Redis writes — potentially 100ms+ latency. With async, the HTTP response returns immediately (post created, 201 response) and fanout happens in the background thread pool.

---

## Dependency Flow

```
FeedController
    └── FeedService
            ├── FollowService (getFolloweeIds, isCelebrity)
            ├── UserService (getUser)
            ├── PostService (getRecentPostsByAuthors, toResponse)
            ├── FeedCacheService (getFeedPostIds — Redis ZREVRANGE)
            └── PostRepository (findByAuthorIdInOrderByCreatedAtDesc — cold-start fallback)

PostController
    └── PostService
            ├── UserService (validate author exists)
            ├── MediaService (resolveCdnUrls — validate UPLOADED status)
            ├── PostRepository (save)
            ├── UserRepository (incrementPostCount)
            ├── PostCacheService (cache post response)
            └── FanoutService (@Async) → FollowService, FeedCacheService

MediaController
    └── MediaService
            └── MediaRepository (save, findById)

UserController
    ├── UserService (CRUD)
    └── FollowService
            ├── FollowRepository (save, delete, findFolloweeIds, findFollowerIds)
            └── UserRepository (incrementFollowerCount, incrementFollowingCount, setCelebrity, getFollowerCount)
```

---

## Key Invariants

1. **Posts reference only UPLOADED media** — enforced by `MediaService.resolveCdnUrls()` which throws IllegalStateException if status != UPLOADED
2. **Celebrity posts are never fanned out** — enforced by `FanoutService.fanoutSync()` which returns early if author is celebrity
3. **Feed ZSET is capped at 1000 posts** — enforced by `FeedCacheService.addToFeed()` ZREMRANGEBYRANK after every ZADD
4. **Celebrity flag flips at follower threshold** — enforced by `FollowService.follow()` and `unfollow()` which call `setCelebrity()` after updating counts
5. **Denormalized counts are always accurate** — maintained by atomic `incrementFollowerCount`, `incrementFollowingCount`, `incrementPostCount` in UserRepository
6. **Redis failures degrade gracefully** — all cache operations wrapped in try/catch; feed reads fall back to DB on cache miss
7. **Follow relationship (followerId, followeeId) is unique** — enforced by @UniqueConstraint on Follow entity + service-level existsBy check
8. **Fanout runs asynchronously** — enforced by @Async("fanoutExecutor") so post creation doesn't block on Redis writes
