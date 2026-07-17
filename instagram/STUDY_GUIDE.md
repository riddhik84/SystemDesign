# Instagram System Design — Interview Study Guide

## Problem Overview
**Question:** Design a photo and video sharing social media platform like Instagram.

**Difficulty:** Mid-to-Senior Level (L4-L6)
**Duration:** 45 minutes

## Key Requirements (5 minutes)

### Functional
1. **Media Upload** — Photo (≤8MB), video (≤4GB), carousel posts; presigned upload URLs
2. **Create Posts** — Photos, videos, carousels with captions (≤2200 chars)
3. **Follow/Unfollow** — Build social graph; denormalized counts (followers, following, posts)
4. **Feed Generation** — Reverse-chronological feed of followed users' posts; paginated
5. **Celebrity Detection** — Auto-flag users above follower threshold; special feed treatment

### Non-Functional
1. **Scale**: 500M DAU, 100M posts/day
2. **Feed Latency**: p99 < 500ms
3. **Media Size**: Photos ≤ 8MB, Videos ≤ 4GB
4. **Availability**: 99.9% (few hours downtime/year)
5. **Consistency**: Eventual OK for feeds (seconds stale), immediate for follow/unfollow

---

## Capacity Estimation (5 minutes)

```
Users: 500M DAU
Posts: 100M posts/day = 1,157/sec write
Feed reads: 500M users × 10 reads/day = 5B reads/day = 57,870 QPS

Storage (annual):
  Posts: 100M/day × 365 × 500 bytes = 18TB/year metadata
  Media (S3): 100M/day × 2MB avg × 365 = 73PB/year

Redis feed cache:
  Assume 200M active users with cached feeds
  200M users × 1000 post IDs/feed × 40 bytes/entry = 8TB
  With 3× replication = 24TB Redis cluster

Write amplification (fanout-on-write):
  Average user: 500 followers × 1,157 posts/sec = ~580K fanout writes/sec
  Celebrity (10M followers): would be 11B writes/sec → INFEASIBLE
  Solution: hybrid fanout (skip celebrities)
```

---

## High-Level Architecture (10 minutes)

```
Client
  ↓
CDN (photos/videos via CloudFront)
  ↓
Load Balancer
  ↓
API Servers (Spring Boot, stateless, scale horizontally)
  ├→ Redis Cache (feed ZSETs: insta:feed:{userId}, post details cache)
  ├→ PostgreSQL/MySQL (users, posts, follows, media metadata)
  └→ S3 (actual photo/video blobs)

Write Path:
  1. POST /api/media → generate presigned upload URL
  2. Client uploads directly to S3
  3. POST /api/media/{id}/complete → mark uploaded
  4. POST /api/posts → create post, trigger async fanout

Feed Read Path:
  1. GET /api/feed → FeedService.getFeed(userId)
  2. HYBRID: merge Redis precomputed + DB celebrity posts + DB fallback
  3. Hydrate post IDs to PostResponse DTOs
  4. Return paginated feed
```

---

## Critical Design Decisions (20 minutes)

### 1. Feed Generation Strategy: Fanout-on-Write vs. Fanout-on-Read vs. HYBRID ⭐⭐⭐ (THE CORE PROBLEM)

**The central challenge:** How do you generate a personalized feed for 500M users in real-time when each user may follow hundreds/thousands of accounts?

**Options considered:**

| Approach | How It Works | Pros | Cons | When to Use |
|----------|-------------|------|------|-------------|
| ❌ **Fanout-on-Read** (pull) | On every feed request, query DB for recent posts from all followed users, sort, paginate | ✅ No write amplification<br>✅ Immediately consistent<br>✅ Works for any user | ❌ Expensive DB query per request<br>❌ High latency (query + sort)<br>❌ Scales poorly with follower count | Low-scale apps (<1M users) |
| ⚠️ **Fanout-on-Write** (push) | When user posts, write post ID to all followers' Redis feeds (pre-compute) | ✅ Fast reads (O(1) Redis range scan)<br>✅ Scales to billions of reads<br>✅ Predictable latency | ❌ Write amplification (celebrity with 10M followers = 10M Redis writes)<br>❌ Async lag (eventual consistency) | Normal users only |
| ✅✅ **HYBRID** (push + pull) | Fanout-on-write for normal users; fanout-on-read for celebrities; merge both on feed read | ✅ Balances read/write cost<br>✅ Handles celebrity hot-keys<br>✅ Best of both worlds | ❌ Complex implementation<br>❌ Eventual consistency<br>❌ Requires merge logic | Production Instagram/Twitter |

**Chosen: HYBRID FANOUT**

#### How It Works (Instagram Production Implementation)

**THREE data sources merged by `FeedService.getFeed`:**

1. **FANOUT-ON-WRITE (precomputed):** Redis ZSET `insta:feed:{userId}`
   - Member = postId
   - Score = createdAt epoch millis
   - Populated when a **normal user** creates a post (FanoutService pushes to all followers)
   - Trimmed to keep only the newest 1,000 posts per user (configurable: `app.feed.max-cached-feed`)

2. **FANOUT-ON-READ (celebrity):** DB query for recent celebrity posts
   - When a user follows a celebrity (≥100K followers), that celebrity's posts are **NOT** fanned out
   - Instead, FeedService queries the DB for all posts by celebrity followees in the **last 24 hours** (configurable: `app.feed.celebrity-lookback-hours`)
   - Real-time data (no Redis lag)

3. **COLD-START FALLBACK:** DB query when Redis feed is empty
   - New users or cache misses: Redis ZSET is empty
   - FeedService queries the DB for recent posts by **all** followed users
   - Bootstraps the feed until the cache is populated

**Merge logic:**
```java
// FeedService.getFeed(userId, page, pageSize)
List<String> precomputedPostIds = feedCacheService.getFeedPostIds(userId, offset, limit);
List<Post> celebrityPosts = postService.getRecentPostsByAuthors(celebrityFolloweeIds, cutoff, limit);
List<Post> fallbackPosts = coldStart ? postRepository.findByAuthorIdIn(...) : [];

// Deduplicate by post ID, sort by createdAt desc, paginate
Map<String, Post> postMap = mergeAndDedupe(precomputed, celebrity, fallback);
List<Post> sorted = postMap.values().sortBy(createdAt DESC);
List<Post> page = sorted.subList(page * pageSize, (page+1) * pageSize);
```

**Interview talking points:**
- "Fanout-on-write is O(N) writes per post where N = follower count. For Kylie Jenner (400M followers), that's 400M Redis writes — infeasible."
- "Hybrid fanout: push to small audiences (<100K), pull from large audiences (>100K). The merge happens at read time."
- "Celebrity threshold is tuned via config: 100K followers is the default cutoff. Monitor fanout latency and adjust."
- "Redis ZSET stores only post IDs, not full post data. Hydration happens via cache-aside on PostService."
- "Cold-start fallback ensures new users get a feed even if Redis is empty. After their first feed read, async fanout will populate the cache."

---

### 2. Celebrity Detection & Automatic Flag Toggle ⭐⭐⭐

**Problem:** How do you identify "celebrity" users to skip fanout?

**Solution: Denormalized followerCount + Automatic Flag Flip**

```java
@Transactional
public void follow(String followerId, String followeeId) {
    // 1. Save follow relationship
    followRepository.save(new Follow(followerId, followeeId));

    // 2. Increment follower count
    userRepository.incrementFollowerCount(followeeId, 1);

    // 3. Check if followee crosses celebrity threshold
    Long followerCount = userRepository.getFollowerCount(followeeId);
    if (followerCount >= celebrityFollowerThreshold) {
        userRepository.setCelebrity(followeeId, true);
        log.info("User id={} became celebrity with {} followers", followeeId, followerCount);
    }
}
```

**Why denormalize followerCount?**
- Query: `SELECT COUNT(*) FROM follows WHERE followee_id = ?` on every follow/unfollow would be expensive
- Denormalized count: single column on User row, updated atomically
- Trade-off: slight consistency risk (rare race condition), but DB transaction guarantees correctness

**Celebrity flag behavior:**
- **Followers < 100K:** Celebrity = false → fanout-on-write (FanoutService pushes to all followers)
- **Followers ≥ 100K:** Celebrity = true → fanout-on-read (FeedService pulls from DB)
- **Dynamic:** Flag toggles automatically as follower count crosses threshold in either direction

**Interview talking points:**
- "Celebrity detection is NOT static. As a user gains followers, they transition from push to pull."
- "The threshold is configurable: `app.feed.celebrity-follower-threshold = 100000`. Tune based on fanout write capacity."
- "Skip fanout entirely for celebrities: FanoutService checks `isCelebrity(authorId)` and returns early."
- "Eventual consistency: if a user becomes a celebrity mid-fanout, some followers may get the post in Redis, others in DB. The merge logic deduplicates."

---

### 3. Media Upload Flow: Presigned URLs ⭐⭐

**Problem:** Uploading 4GB videos through your API servers is a bottleneck. How do you offload this?

**Solution: Two-Phase Upload with Presigned URLs**

**Phase 1: Request upload URL**
```
POST /api/media
{
  "uploaderId": "user123",
  "type": "VIDEO",
  "sizeBytes": 4000000000
}
→ Response:
{
  "mediaId": "media456",
  "uploadUrl": "https://uploads.example.com/user123/media456?sig=abc123",
  "cdnUrl": "https://cdn.example.com/user123/media456",
  "maxSizeBytes": 4294967296
}
```

Backend:
1. Validate size against type limits (photo: 8MB, video: 4GB)
2. Create `Media` record with status = PENDING
3. Generate presigned upload URL (S3 PutObject with expiration, e.g., 1 hour)
4. Generate CDN URL (CloudFront distribution)
5. Return both URLs to client

**Phase 2: Client uploads directly to S3**
```
PUT https://uploads.example.com/user123/media456?sig=abc123
(4GB video blob, bypasses API servers)
```

**Phase 3: Mark upload complete**
```
POST /api/media/media456/complete
```
Backend:
1. Update `Media` record status = UPLOADED
2. Now the media can be used in a post

**Phase 4: Create post**
```
POST /api/posts
{
  "authorId": "user123",
  "caption": "Sunset in Santorini",
  "mediaType": "PHOTO",
  "mediaIds": ["media456"]  ← reference already-uploaded media
}
```
Backend:
1. Validate all media IDs are UPLOADED (reject if PENDING)
2. Resolve media IDs to CDN URLs
3. Save Post with mediaUrls = [cdnUrl1, cdnUrl2, ...]
4. Trigger fanout

**Interview talking points:**
- "Presigned URLs are S3 signed URLs with time-limited write permissions. Client uploads directly to S3."
- "API servers never touch the 4GB blob — bandwidth savings."
- "Media validation happens at two points: size check when requesting URL, status check when creating post."
- "CDN serves media reads (CloudFront geo-distributed edge locations)."
- "Extension: Add virus scanning via S3 Lambda trigger before marking UPLOADED."

---

### 4. Redis Feed Cache Design: ZSET with Trimming ⭐⭐

**Data structure:**
```
Key: insta:feed:{userId}
Type: ZSET (sorted set)
Member: postId (string)
Score: createdAt (epoch millis)
TTL: 86400 seconds (1 day)
```

**Fanout operation (write):**
```java
// FanoutService.fanout(Post post)
List<String> followerIds = followService.getFollowerIds(post.getAuthorId());
long scoreMs = post.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();

for (String followerId : followerIds) {
    redis.opsForZSet().add("insta:feed:" + followerId, post.getId(), scoreMs);
    
    // Trim to keep only newest 1000 posts
    long size = redis.opsForZSet().zCard(key);
    if (size > 1000) {
        redis.opsForZSet().removeRange(key, 0, size - 1001);
    }
    
    redis.expire(key, Duration.ofDays(1));
}
```

**Feed read operation:**
```java
// FeedService.getFeed(userId, page=0, pageSize=20)
Set<String> postIds = redis.opsForZSet().reverseRange(
    "insta:feed:" + userId,
    0,  // offset
    19  // limit (fetch top 20 newest)
);
// Returns post IDs in reverse-chronological order (highest score first)
```

**Why ZSET over LIST?**
- LIST: append-only, range queries are O(N), no sorting by timestamp
- ZSET: sorted by score (createdAt), range queries are O(log N), supports reverse-chronological pagination
- ZSET supports deduplication: adding same postId twice is a no-op (idempotent)

**Trimming strategy:**
- Keep only the newest 1,000 posts per user feed (configurable: `app.feed.max-cached-feed`)
- Older posts are pruned automatically on every fanout write
- Trade-off: infinite scroll beyond 1,000 posts falls back to DB

**Graceful degradation:**
```java
try {
    return redis.opsForZSet().reverseRange(key, offset, limit);
} catch (Exception e) {
    log.warn("Redis failure: {}", e.getMessage());
    return List.of();  // Return empty, trigger DB fallback
}
```

**Interview talking points:**
- "ZSET score is epoch millis, so reverse range query gives reverse-chronological feed."
- "Trim to 1,000 posts to cap memory. A user with 1,000 cached posts × 40 bytes/entry = 40KB per user."
- "500M users × 40KB = 20TB Redis cluster (with 3× replication = 60TB)."
- "TTL eviction: feeds expire after 1 day. User returns after 2 days? Cold-start fallback fetches from DB."

---

### 5. Async Fanout: @Async Thread Pool ⭐⭐

**Problem:** Fanout write amplification blocks post creation. A user with 10K followers would take 10K Redis writes.

**Solution: Asynchronous Fanout**

```java
// PostService.createPost
@Transactional
public PostResponse createPost(CreatePostRequest req) {
    Post saved = postRepository.save(post);

    // Publish event — fanout is triggered AFTER this transaction commits (non-blocking)
    eventPublisher.publishEvent(new PostCreatedEvent(this, saved));

    return toResponse(saved);
}

// FanoutService — runs after commit, on the fanoutExecutor thread pool
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async("fanoutExecutor")
public void onPostCreated(PostCreatedEvent event) {
    Post post = event.getPost();
    if (followService.isCelebrity(post.getAuthorId())) return;   // celebrities use fanout-on-read
    List<String> followerIds = followService.getFollowerIds(post.getAuthorId());
    feedCacheService.addToManyFeeds(followerIds, post.getId(), scoreMs);
}
```

> **Why `AFTER_COMMIT`?** Firing fanout directly as `@Async` from inside `createPost` lets the background thread read the DB before the post commits (read-before-commit race). Binding it to the commit phase guarantees the post is visible before fanout runs.

**Thread pool config:**
```java
@Bean(name = "fanoutExecutor")
public Executor fanoutExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(50);
    executor.setQueueCapacity(1000);
    executor.setThreadNamePrefix("fanout-");
    return executor;
}
```

**Consequences:**
- **Post creation returns immediately** (before fanout completes)
- **Eventual consistency:** Followers' feeds may take seconds to show the new post
- **Fanout lag monitoring:** Track fanout duration; if >5 seconds, scale up thread pool

**Interview talking points:**
- "Async fanout decouples write latency from follower count. Post creation is O(1), fanout is O(N) in the background."
- "Trade-off: eventual consistency. User posts a photo, refreshes immediately — it might not appear yet."
- "In production, use a message queue (SQS/Kafka): POST → Kafka → FanoutWorker cluster → Redis."
- "Retry logic: if fanout fails (Redis down), requeue the message. Idempotent ZADD ensures no duplicates."

---

## Data Model (5 minutes)

```sql
users
  id UUID PK
  username VARCHAR UNIQUE
  display_name VARCHAR
  email VARCHAR NOT NULL
  bio VARCHAR(500)
  profile_picture_url VARCHAR
  follower_count BIGINT DEFAULT 0    -- denormalized
  following_count BIGINT DEFAULT 0   -- denormalized
  post_count BIGINT DEFAULT 0        -- denormalized
  is_celebrity BOOLEAN DEFAULT FALSE -- auto-set at threshold
  created_at TIMESTAMP

follows
  id UUID PK
  follower_id UUID                   -- index
  followee_id UUID                   -- index
  created_at TIMESTAMP
  UNIQUE(follower_id, followee_id)   -- prevent double-follow

posts
  id UUID PK
  author_id UUID                     -- index
  caption VARCHAR(2200)
  media_type ENUM('PHOTO', 'VIDEO', 'CAROUSEL')
  created_at TIMESTAMP               -- index for sorting
  INDEX(author_id, created_at)       -- celebrity fanout-on-read query

post_media_urls
  post_id UUID FK
  media_url VARCHAR                  -- CDN URL

media
  id UUID PK
  uploader_id UUID
  type ENUM('PHOTO', 'VIDEO')
  size_bytes BIGINT
  blob_key VARCHAR                   -- S3 key
  upload_url VARCHAR                 -- presigned URL
  cdn_url VARCHAR                    -- CloudFront URL
  status ENUM('PENDING', 'UPLOADED')
  created_at TIMESTAMP
```

**Indexes:**
- `(author_id, created_at)` on posts — enables fanout-on-read query: "get posts by authors X,Y,Z after timestamp T"
- `(follower_id, followee_id)` UNIQUE on follows — enforces one follow relationship
- `(username)` on users — login, profile lookups

---

## Scaling (5 minutes)

**Read path:**
```
57,870 feed QPS
× 80% served by Redis (precomputed feeds) = 46,296 QPS Redis
× 20% cold-start + celebrity = 11,574 QPS DB
```

**Write path:**
```
1,157 posts/sec
× 500 avg followers = ~580K fanout writes/sec to Redis
Async fanout workers: 50 threads × 11,600 writes/sec/thread = 580K capacity
```

**Redis cluster sizing:**
```
200M active users × 1,000 posts/feed × 40 bytes/entry = 8TB
With 3× replication = 24TB
24TB / 500GB per node = 48 Redis nodes
```

**Database sharding:**
```
Shard by user_id (consistent hashing)
Shard 1: users A-M, their posts, follows
Shard 2: users N-Z, their posts, follows
Cross-shard queries: celebrity fanout-on-read (query multiple shards)
```

**S3/CloudFront:**
```
100M posts/day × 2MB avg = 200TB/day = 73PB/year
CloudFront: 200+ edge locations for low-latency media delivery
```

---

## Interview Talking Track

### Opening (2 min)
"I'll design Instagram — a photo/video sharing platform at 500M DAU, 100M posts/day. The core challenges are feed generation at scale and media upload/delivery. Feed generation requires a hybrid fanout strategy to handle celebrity hot-keys..."

### Requirements (5 min)
- Clarify: Story-like ephemeral content? (out of scope, focus on feed)
- Clarify: Likes/comments? (out of scope for MVP, can add later)
- Clarify: Feed algorithm? (reverse-chronological for simplicity, can add ranking later)
- Out of scope: DMs, search, hashtags, discovery, ads

### Capacity (5 min)
"500M DAU × 10 feed reads/day = 5B reads/day = 57,870 QPS. Posts: 100M/day = 1,157 writes/sec. Write amplification: fanout to 500 followers = 580K Redis writes/sec..."

### High-Level (10 min)
"Media uploads go directly to S3 via presigned URLs. Post creation triggers async fanout. Feed reads merge three sources: Redis precomputed feed, DB celebrity posts, DB cold-start fallback..."

### Deep Dives (20 min)

**"How do you generate feeds at 500M users?"**
- Hybrid fanout: push to normal users (fanout-on-write), pull from celebrities (fanout-on-read)
- Redis ZSET: `insta:feed:{userId}` stores post IDs sorted by createdAt
- Merge: precomputed + celebrity + fallback, dedupe, sort, paginate
- Celebrity detection: follower count ≥ 100K triggers skip fanout

**"Why hybrid? Why not fanout-on-write everywhere?"**
- Write amplification: celebrity with 10M followers = 10M Redis writes
- At 1,000 posts/sec from celebrities, that's 10B writes/sec — infeasible
- Hybrid: only 20% of users are celebrities, but they account for 80% of potential fanout writes

**"How do you handle 4GB video uploads?"**
- Presigned S3 URLs: client uploads directly to S3
- API servers generate presigned URL with time limit (1 hour)
- Media validation: size check at URL request, status check at post creation
- CDN (CloudFront) serves videos from edge locations

**"What if Redis goes down?"**
- Graceful degradation: Redis failure returns empty list
- FeedService falls back to cold-start DB query (fanout-on-read for all users)
- Feed latency increases (500ms → 2s), but app stays alive

**"How do you prevent a celebrity from creating 1,000 posts/sec and overwhelming the DB?"**
- Rate limiting: max 10 posts/day per user (configurable)
- Celebrity fanout-on-read query: `WHERE author_id IN (...) AND created_at > NOW() - 24h`
- With index on (author_id, created_at), query is O(log N) per celebrity
- If user follows 50 celebrities, query scans 50 × 24h lookback window

---

## Common Pitfalls

1. ❌ Fanout-on-write for all users (celebrity hot-key problem)
2. ❌ Fanout-on-read for all users (expensive DB query on every feed request)
3. ❌ Uploading 4GB videos through API servers (bandwidth bottleneck)
4. ❌ Storing full post data in Redis ZSET (memory explosion; store only post IDs)
5. ❌ No trimming of Redis feeds (unbounded growth, OOM)
6. ❌ Synchronous fanout (post creation blocks on Redis writes)
7. ❌ No cold-start fallback (new users or cache misses get empty feeds)
8. ❌ Celebrity threshold is static (manually configured list instead of automatic detection)

---

## Follow-Up Questions

**"How do you add likes and comments?"**
- New tables: `likes (user_id, post_id, created_at)`, `comments (id, post_id, user_id, text, created_at)`
- Denormalize counts: `posts.like_count`, `posts.comment_count`
- Cache: `insta:post:{postId}:likes` (Redis ZSET), `insta:post:{postId}:comments` (Redis LIST)
- Invalidate post cache on like/comment

**"How do you rank the feed algorithmically (vs. chronological)?"**
- Ranking signal features: recency, author engagement (likes/comments), user affinity (view time, past likes)
- ML model: train on user engagement data (clicks, likes, time spent)
- Hybrid approach: generate candidate posts (hybrid fanout), then rank top 100 with ML model
- Serve top 20 to user

**"How do you handle post deletes?"**
- Soft delete: `posts.deleted_at TIMESTAMP`
- Remove from Redis feeds: iterate all followers' feeds (expensive)
- Lazy cleanup: on feed read, filter out deleted posts before returning
- Trade-off: stale deleted posts in cache for 1 day (TTL), then evicted

**"How do you implement Stories (24-hour ephemeral content)?"**
- Separate table: `stories (id, author_id, media_url, expires_at TIMESTAMP)`
- No fanout: stories are fetched on-demand (user opens Stories tab)
- Query: "get all stories from users I follow, created in last 24h, not expired"
- Cache story ring per user: `insta:stories:{userId}` (Redis LIST, 24h TTL)

**"How do you prevent fake followers?"**
- Rate limiting: max 100 follows/hour per user
- CAPTCHA on suspicious activity
- ML classifier: detect bot accounts (follow/unfollow patterns, creation date)
- Shadowban: celebrity sees fake follower, but fake follower's actions don't count

**"How do you handle celebrity unfollows (fanout-on-read to fanout-on-write transition)?"**
- If a celebrity loses followers and drops below threshold, they transition back to fanout-on-write
- Next post triggers full fanout to remaining followers
- Old posts (from when they were celebrity) remain in DB-only, not fanned out retroactively
- Trade-off: temporary inconsistency until next post

---

## Key Numbers to Know

```
Users: 500M DAU
Posts: 100M/day = 1,157/sec
Feed reads: 5B/day = 57,870 QPS
Write amplification: 500 followers × 1,157 posts/sec = 580K Redis writes/sec

Celebrity threshold: 100K followers (configurable)
Feed cache size: 1,000 posts per user (40KB)
Total Redis: 200M users × 40KB = 8TB (24TB with replication)

Media size limits: Photo 8MB, Video 4GB
Media storage: 100M posts/day × 2MB avg = 200TB/day = 73PB/year

Feed latency target: p99 < 500ms
Feed sources: Redis precomputed + DB celebrity (24h lookback) + DB cold-start
```

---

## One-Page Cheat Sheet

```
INSTAGRAM SYSTEM DESIGN — CHEAT SHEET

CORE CHALLENGE: Feed generation at 500M DAU, 100M posts/day

HYBRID FANOUT (THE KEY DECISION):
  Normal users (<100K followers): FANOUT-ON-WRITE (push to Redis)
  Celebrities (≥100K followers): FANOUT-ON-READ (pull from DB)
  Merge: precomputed + celebrity + cold-start fallback

REDIS FEED CACHE:
  Key: insta:feed:{userId}
  Type: ZSET (member=postId, score=createdAt epoch ms)
  Trim: keep newest 1000 posts
  TTL: 1 day
  Graceful degradation: Redis failure → DB fallback

FANOUT OPERATION:
  1. User posts → PostService.createPost saves to DB, publishes PostCreatedEvent
  2. After commit → @TransactionalEventListener(AFTER_COMMIT) + @Async → FanoutService.onPostCreated
  3. Check isCelebrity: if true, skip; if false, push to all followers' Redis feeds
  4. FeedService.getFeed merges Redis + DB celebrity + DB fallback

CELEBRITY DETECTION:
  Denormalized followerCount on User row
  On follow/unfollow: increment count, check threshold
  If count ≥ 100K: set isCelebrity = true
  Dynamic: flag toggles automatically as followers cross threshold

MEDIA UPLOAD:
  1. POST /api/media → generate presigned S3 upload URL
  2. Client uploads directly to S3 (bypass API servers)
  3. POST /api/media/{id}/complete → mark UPLOADED
  4. POST /api/posts → reference uploaded media IDs
  5. CDN (CloudFront) serves media reads

FEED MERGE LOGIC:
  1. Fetch precomputed post IDs from Redis ZSET
  2. Fetch celebrity posts from DB (last 24h)
  3. If precomputed empty (cold start), fetch all posts from DB
  4. Dedupe by post ID, sort by createdAt desc, paginate
  5. Hydrate post IDs to PostResponse DTOs

SCALING:
  Redis: 48 nodes (24TB with replication)
  DB: shard by user_id (consistent hashing)
  S3: 73PB/year media storage
  Async fanout: 50 threads × 11,600 writes/sec = 580K capacity
