# Instagram Photo Sharing Platform — System Design

A scalable implementation of an Instagram-like photo and video sharing platform with hybrid fan-out feed generation, CDN-based media delivery, and follow graph management. Built with Spring Boot to handle 500M daily active users, 100M posts/day, and sub-500ms feed latency.

## Overview

This project demonstrates the architecture and implementation of a social media photo/video sharing platform where users can:
- Upload photos (up to 8MB) and videos (up to 4GB) via presigned URLs
- Create posts with single photos, videos, or carousel albums with captions
- Follow and unfollow other users
- View a personalized feed of posts from followed users in reverse chronological order
- Experience optimized feed delivery through a hybrid fan-out strategy (push for normal users, pull for celebrities)

## System Requirements

### Functional Requirements
- **Media Upload**: Request presigned upload URLs; upload to simulated CDN; mark as complete
- **Create Posts**: Attach uploaded media (photo/video/carousel) with optional caption (max 2200 chars)
- **Follow/Unfollow**: Build a social graph; maintain follower/following counts
- **Personalized Feed**: Reverse-chronological feed of posts from followed users; paginated results
- **Celebrity Handling**: Automatic promotion to celebrity status at 100K followers; different feed strategy

### Non-Functional Requirements
- **Scale**: 500M daily active users (DAU), 100M posts/day (~1157 posts/second)
- **Feed latency**: p99 < 500ms
- **Media limits**: Photos ≤ 8MB, Videos ≤ 4GB
- **Feed freshness**: Real-time for normal users, eventual consistency for celebrity followers
- **Availability**: 99.9% uptime

## Capacity Estimation

### Daily Active Users & Post Volume
```
DAU: 500M users
Posts/day: 100M
Avg posts per DAU: 100M / 500M = 0.2 posts/day = 1 post every 5 days
```

### QPS Breakdown
```
WRITE QPS:
  Post creation: 100M posts/day ÷ 86400s = 1157 posts/sec
  Follow/unfollow: ~5% of DAU per day = 25M/day = 289/sec
  Total write QPS: ~1500/sec

READ QPS:
  Feed reads: 500M DAU × 30 feed refreshes/day = 15B requests/day
              15B ÷ 86400s = 173,600 QPS (peak: 300K QPS)
  Post detail: 15B feed impressions × 0.1 click-through = 1.5B/day = 17,300 QPS
  Total read QPS: ~190K QPS average, ~320K peak
```

### Fanout Write Amplification
```
Assumption: Avg 200 followers per user (excluding celebrities)
Normal user post: 1 write → 200 Redis feed updates = 200× amplification
Daily fanout writes: 100M posts × 200 followers = 20B Redis writes/day
                      20B ÷ 86400s = 231,000 writes/sec to Redis

Celebrity post (100K+ followers): NO fanout → 0 Redis writes
Instead: FeedService queries DB on every read (fanout-on-read)
```

### Storage (30-day retention for estimation)
```
POSTS TABLE:
  1 post ≈ 500 bytes (ID, authorId, caption, mediaType, timestamps, media URLs)
  100M posts/day × 30 days = 3B posts
  3B × 500 bytes = 1.5 TB

MEDIA METADATA TABLE:
  1 media record ≈ 300 bytes (ID, uploaderId, blobKey, URLs, type, status)
  100M posts × 1.5 media/post = 150M media/day × 30 days = 4.5B media records
  4.5B × 300 bytes = 1.35 TB

MEDIA BLOBS (CDN/object storage):
  Avg media size: (photos + videos blended) ≈ 5MB
  150M media/day × 5MB = 750 TB/day
  30 days × 750 TB = 22.5 PB

USERS TABLE:
  500M users × 500 bytes = 250 GB

FOLLOWS TABLE:
  500M users × 200 follows avg = 100B relationships
  100B × 50 bytes = 5 TB

REDIS FEED CACHE:
  1 feed entry ≈ 24 bytes (postId: 16 bytes UUID + score: 8 bytes)
  Max cached feed per user: 1000 posts (capped)
  Active users with cached feeds: 100M (20% of DAU)
  100M users × 1000 posts × 24 bytes = 2.4 TB

TOTAL METADATA STORAGE: ~9 TB (database)
TOTAL BLOB STORAGE: 22.5 PB (CDN/S3)
TOTAL REDIS CACHE: 2.4 TB
```

### Bandwidth
```
UPLOAD:
  100M posts/day × 1.5 media × 5MB = 750 TB/day
  750 TB/day ÷ 86400s = 8.7 GB/sec = 69 Gbps

DOWNLOAD (CDN):
  15B feed impressions/day × 0.8 images load × 200 KB thumbnail = 2.4 PB/day
  2.4 PB/day ÷ 86400s = 27.8 TB/sec = 222 Tbps (CDN-distributed)
```

## Core Entities & Data Model

### User
```java
id: String (UUID)
username: String (unique, indexed)
displayName: String
email: String
bio: String (max 500 chars)
profilePictureUrl: String
followerCount: long (denormalized)
followingCount: long (denormalized)
postCount: long (denormalized)
celebrity: boolean (auto-promoted at 100K followers)
createdAt: LocalDateTime
```

### Post
```java
id: String (UUID)
authorId: String (indexed)
caption: String (max 2200 chars)
mediaType: enum (PHOTO, VIDEO, CAROUSEL)
mediaUrls: List<String> (CDN URLs)
createdAt: LocalDateTime (indexed, used for feed sorting)
```

### Media
```java
id: String (UUID)
uploaderId: String
blobKey: String (object storage key: uploaderId/mediaId)
uploadUrl: String (presigned PUT URL, simulated)
cdnUrl: String (public CDN URL for read)
type: enum (PHOTO, VIDEO, CAROUSEL)
status: enum (PENDING, UPLOADED)
sizeBytes: long
createdAt: LocalDateTime
```

### Follow
```java
id: String (UUID)
followerId: String (indexed)
followeeId: String (indexed)
createdAt: LocalDateTime
UNIQUE constraint: (followerId, followeeId)
```

## API Design

### User Management
```
POST /api/users
  Request: { username, displayName, email, bio, profilePictureUrl }
  Response: { id, username, displayName, email, bio, profilePictureUrl,
              followerCount, followingCount, postCount, celebrity, createdAt }
  Status: 201 Created

GET /api/users/{userId}
  Response: UserResponse (same as POST)
  Status: 200 OK
```

### Follow Graph
```
POST /api/users/{userId}/follow?targetId={targetId}
  Request: No body (userId from path, targetId from query param)
  Response: No content
  Status: 204 No Content
  Side effects:
    - Creates Follow record
    - Increments follower/following counts
    - Promotes targetId to celebrity if count >= 100K

DELETE /api/users/{userId}/follow?targetId={targetId}
  Request: No body
  Response: No content
  Status: 204 No Content
  Side effects:
    - Deletes Follow record
    - Decrements follower/following counts
    - Demotes targetId from celebrity if count < 100K

GET /api/users/{userId}/followers
  Response: [ "followerId1", "followerId2", ... ]
  Status: 200 OK

GET /api/users/{userId}/following
  Response: [ "followeeId1", "followeeId2", ... ]
  Status: 200 OK
```

### Media Upload (2-Phase)
```
POST /api/media/upload-url
  Request: { uploaderId, type: "PHOTO"|"VIDEO"|"CAROUSEL", sizeBytes }
  Response: {
    mediaId: "uuid",
    uploadUrl: "https://uploads.instagram-clone.example.com/{uploaderId}/{mediaId}?sig=SIMULATED",
    blobKey: "{uploaderId}/{mediaId}",
    cdnUrl: "https://cdn.instagram-clone.example.com/{uploaderId}/{mediaId}",
    maxSizeBytes: 8388608 (for PHOTO) or 4294967296 (for VIDEO)
  }
  Status: 200 OK
  Notes: Client uploads to uploadUrl (simulated), then calls /complete

POST /api/media/{mediaId}/complete
  Request: No body
  Response: No content
  Status: 200 OK
  Side effect: Sets media.status = UPLOADED (now usable in posts)
```

### Post Creation
```
POST /api/posts
  Request: {
    authorId: "uuid",
    caption: "Beach sunset #travel",
    mediaType: "PHOTO"|"VIDEO"|"CAROUSEL",
    mediaIds: ["mediaId1", "mediaId2", ...]
  }
  Response: {
    id: "postId",
    authorId: "uuid",
    authorUsername: "john_doe",
    caption: "Beach sunset #travel",
    mediaType: "PHOTO",
    mediaUrls: [ "https://cdn.instagram-clone.example.com/..." ],
    createdAt: "2026-07-17T10:30:00"
  }
  Status: 201 Created
  Side effects:
    - Validates mediaIds are UPLOADED
    - Creates Post record with CDN URLs
    - Increments author.postCount
    - Triggers async fanout to followers (if author is not celebrity)

GET /api/posts/{postId}
  Response: PostResponse (same as POST)
  Status: 200 OK
```

### Feed
```
GET /api/feed?userId={userId}&page=0&pageSize=20
  Response: {
    userId: "uuid",
    posts: [ PostResponse, PostResponse, ... ],
    page: 0,
    pageSize: 20,
    hasMore: true,
    servedAtEpochMs: 1721213400000,
    feedStrategyNote: "FANOUT_ON_WRITE (precomputed: 50 post IDs from Redis) + FANOUT_ON_READ (celebrity: 10 posts from 2 celebrities, last 24h)"
  }
  Status: 200 OK
  Notes:
    - Reverse chronological order (newest first)
    - Hybrid fanout: merges Redis precomputed feed + DB celebrity posts + DB cold-start fallback
    - Cold-start: if Redis feed is empty, fetches recent posts from DB
    - feedStrategyNote explains which sources contributed
```

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                     Clients (Web / Mobile App)                   │
└──────────────────────────┬───────────────────────────────────────┘
                           │ HTTPS
                           v
┌──────────────────────────────────────────────────────────────────┐
│                    API Gateway / Load Balancer                   │
└───────┬──────────────────┬───────────────────┬───────────────────┘
        │                  │                   │
        v                  v                   v
┌──────────────┐  ┌──────────────────┐  ┌──────────────────────────┐
│ UserService  │  │  MediaService    │  │    PostService           │
│              │  │                  │  │                          │
│ - Follow     │  │ - PresignedURLs  │  │ - CreatePost             │
│ - Unfollow   │  │ - Upload/Complete│  │ - GetPost                │
│              │  │                  │  │ - Fanout trigger (async) │
└──────┬───────┘  └────────┬─────────┘  └──────────┬───────────────┘
       │                   │                       │
       │                   │                       v
       │                   │            ┌───────────────────────────┐
       │                   │            │   FanoutService (@Async)  │
       │                   │            │                           │
       │                   │            │ Strategy:                 │
       │                   │            │  - Normal user: push to   │
       │                   │            │    all followers' Redis   │
       │                   │            │    feeds (fanout-on-write)│
       │                   │            │  - Celebrity: skip fanout │
       │                   │            │    (fanout-on-read)       │
       │                   │            └──────────┬────────────────┘
       │                   │                       │
       │                   │                       v
       v                   v                ┌─────────────────────────┐
┌──────────────────────────────────────────┤   Redis Cache Layer     │
│                                          │                         │
│  FEED CACHE (Redis ZSET):               │ Feed: insta:feed:{userId}│
│    Key: insta:feed:{userId}             │   Member: postId         │
│    Value: ZSET (member=postId,          │   Score: createdAt ms    │
│                 score=createdAtMs)      │   Max size: 1000 posts   │
│    Max entries: 1000 (capped)           │   TTL: 1 day             │
│    TTL: 1 day                           │                         │
│                                          │ Post: insta:post:{postId}│
│  POST CACHE (Redis String):             │   TTL: 1 hour            │
│    Key: insta:post:{postId}             │                         │
│    Value: JSON(PostResponse)            │                         │
│    TTL: 1 hour                          │                         │
└───────────────┬──────────────────────────┴─────────────────────────┘
                │
                v
┌──────────────────────────────────────────────────────────────────┐
│                      FeedService (Hybrid Fanout)                 │
│                                                                  │
│  THREE DATA SOURCES MERGED:                                     │
│    1. FANOUT-ON-WRITE (precomputed): Redis ZSET                 │
│         → Fast, pre-pushed for normal users                     │
│    2. FANOUT-ON-READ (celebrity): DB query for recent posts     │
│         → Expensive, but only for celebrity authors             │
│         → Avoids fanout write storm (100K+ followers)           │
│    3. COLD-START FALLBACK: DB query all followed users' posts   │
│         → Used when Redis feed is empty (new user, cache miss)  │
│                                                                  │
│  MERGE LOGIC:                                                   │
│    - Fetch precomputed post IDs from Redis (paginated)          │
│    - Fetch recent celebrity posts from DB (last 24h, configurable)│
│    - If precomputed empty → fetch all recent posts from DB      │
│    - Deduplicate by postId, sort by createdAt desc              │
│    - Apply pagination window (page, pageSize)                   │
│    - Hydrate post IDs → PostResponse DTOs (cache-aside)         │
│                                                                  │
│  NFR TARGET: p99 latency < 500ms                                │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           v
┌──────────────────────────────────────────────────────────────────┐
│                      H2 Database (PostgreSQL in prod)            │
│                                                                  │
│  TABLES:                                                         │
│    users (id PK, username UNIQUE, followerCount, celebrity)     │
│    posts (id PK, authorId FK, caption, mediaType, createdAt)    │
│    post_media_urls (post_id FK, media_url)                      │
│    media (id PK, uploaderId, blobKey, cdnUrl, status)           │
│    follows (id PK, followerId, followeeId, UNIQUE(follower,followee))│
│                                                                  │
│  INDEXES:                                                        │
│    idx_user_username (users.username)                            │
│    idx_post_author (posts.author_id)                             │
│    idx_post_created (posts.created_at) ← critical for feed query│
│    idx_follow_follower (follows.follower_id)                     │
│    idx_follow_followee (follows.followee_id)                     │
└──────────────────────────────────────────────────────────────────┘

                           │
                           v
┌──────────────────────────────────────────────────────────────────┐
│           CDN / Object Storage (S3, CloudFront)                  │
│                                                                  │
│  Blob Key: {uploaderId}/{mediaId}                                │
│  Upload URL: https://uploads.instagram-clone.example.com/...    │
│  CDN URL: https://cdn.instagram-clone.example.com/...           │
│                                                                  │
│  Benefits:                                                       │
│    - Offload media delivery from app servers                     │
│    - Global edge caching (low latency)                           │
│    - Handles 222 Tbps download bandwidth                         │
└──────────────────────────────────────────────────────────────────┘
```

## Deep Dives

### 1. Hybrid Fan-Out: Write vs Read (The Celebrity Problem)

**The Problem:**  
When a celebrity with 10M followers posts, naive fanout-on-write would trigger 10M Redis writes. At 100M posts/day, this explodes write volume and creates hotspot contention.

**Naive Approach (Pure Fanout-on-Write):**
```
Celebrity posts → fanout to 10M followers' Redis feeds
Write QPS: 1157 posts/sec × 10M followers = 11.57B writes/sec
Result: Redis overwhelmed, write latency spikes, fanout queue backlog
```

**Our Approach (Hybrid Fanout):**

#### Fanout-on-Write (Normal Users)
- **When:** User with < 100K followers creates a post
- **How:** `createPost` publishes a `PostCreatedEvent`; `FanoutService.onPostCreated()` runs asynchronously after the transaction commits (`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`)
- **Action:** Push `postId` to all followers' Redis feeds:
  ```
  ZADD insta:feed:{followerId} {createdAtMs} {postId}
  ```
- **Complexity:** O(F) where F = follower count (capped at 100K)
- **Benefit:** Feed reads are instant (just fetch from Redis ZSET)
- **Cost:** Write amplification = follower count (acceptable for normal users)

#### Fanout-on-Read (Celebrities)
- **When:** User with ≥ 100K followers creates a post
- **How:** `FanoutService.fanoutSync()` detects celebrity status and **skips** fanout
- **Action:** No Redis writes
- **Feed Read:** `FeedService.getFeed()` queries DB for recent celebrity posts:
  ```sql
  SELECT * FROM posts
  WHERE authorId IN (celebrity_followee_ids)
    AND createdAt > NOW() - INTERVAL '24 hours'
  ORDER BY createdAt DESC
  LIMIT 60
  ```
- **Complexity:** O(C × P) where C = celebrity followees, P = posts per celebrity (bounded by time window)
- **Benefit:** Eliminates write storm for high-follower accounts
- **Cost:** Slight feed read latency increase (DB query), but amortized across millions of followers

#### Automatic Celebrity Promotion
```java
// FollowService.follow()
userRepository.incrementFollowerCount(followeeId, 1);
Long followerCount = userRepository.getFollowerCount(followeeId);
if (followerCount >= celebrityFollowerThreshold) {
    userRepository.setCelebrity(followeeId, true);
    log.info("User {} became celebrity with {} followers", followeeId, followerCount);
}
```
- Threshold: `app.feed.celebrity-follower-threshold=100000` (configurable)
- Automatic demotion if count drops below threshold

#### Merge Logic (FeedService.getFeed)
```
1. Fetch precomputed feed from Redis (fanout-on-write)
2. Fetch recent celebrity posts from DB (fanout-on-read, last 24h)
3. If Redis feed empty → cold-start fallback (fetch all followed users' posts from DB)
4. Deduplicate by postId (merge all three sources)
5. Sort by createdAt desc
6. Apply pagination (page × pageSize)
7. Hydrate post IDs → PostResponse DTOs
```

**Why Not Pure Fanout-on-Read?**
- 190K feed QPS × DB query latency = unacceptable latency for normal users
- Redis cache hit rate is 80%+, avoids DB load

**Why Not Pure Fanout-on-Write?**
- Celebrity posts create write storms (10M × 1157 posts/sec = 11B writes/sec)
- Redis write capacity limit, hotspot contention on celebrity feed keys

**Hybrid Balances:**
- Normal users: instant feed reads (Redis precomputed)
- Celebrity followers: slight latency increase (DB query) but tolerable
- Celebrity posts: no write amplification

### 2. Media Upload & CDN Delivery with Presigned URLs

**The Problem:**  
Uploading 750 TB/day of media through app servers:
- Saturates app server bandwidth
- Increases latency (upload blocks post creation)
- No geographic optimization (users in Asia upload to US server)

**Our Approach: 2-Phase Upload with Presigned URLs**

#### Phase 1: Request Upload URL
```
Client → POST /api/media/upload-url
         { uploaderId, type: "PHOTO", sizeBytes: 3145728 }
      ← 200 OK
         {
           mediaId: "abc-123",
           uploadUrl: "https://uploads.instagram-clone.example.com/user1/abc-123?sig=SIMULATED",
           blobKey: "user1/abc-123",
           cdnUrl: "https://cdn.instagram-clone.example.com/user1/abc-123",
           maxSizeBytes: 8388608
         }
```
- **Server Action:**
  - Validates size against type limit (PHOTO ≤ 8MB, VIDEO ≤ 4GB)
  - Creates `Media` record with `status=PENDING`
  - Generates `uploadUrl` (simulated presigned PUT URL with 15-min expiry)
  - Generates `cdnUrl` (public read URL)
  - Returns URLs to client

#### Phase 2: Client Uploads Directly to CDN
```
Client → PUT https://uploads.instagram-clone.example.com/user1/abc-123?sig=SIMULATED
         (file bytes, up to maxSizeBytes)
      ← 200 OK (from CDN, not app server)
```
- **Flow:** Client uploads directly to object storage (S3, GCS) via presigned URL
- **Benefit:** Offloads bandwidth from app servers, leverages CDN edge locations
- **Signature Validation:** `?sig=SIMULATED` prevents unauthorized uploads

#### Phase 3: Mark Upload Complete
```
Client → POST /api/media/abc-123/complete
      ← 200 OK (media.status → UPLOADED)
```
- **Server Action:** Sets `media.status = UPLOADED`, now usable in posts

#### Phase 4: Create Post with Uploaded Media
```
Client → POST /api/posts
         { authorId, caption, mediaType: "PHOTO", mediaIds: ["abc-123"] }
      ← 201 Created
         { id, authorId, caption, mediaUrls: ["https://cdn.instagram-clone.example.com/user1/abc-123"], ... }
```
- **Server Action:**
  - Validates all `mediaIds` have `status=UPLOADED`
  - Resolves `mediaIds` → `cdnUrl` list
  - Creates `Post` record with CDN URLs (not media IDs)
  - Triggers async fanout

**Why Presigned URLs?**
- **Bandwidth Offload:** 69 Gbps upload traffic bypasses app servers
- **Latency:** CDN edge locations closer to users (global distribution)
- **Security:** Time-limited, signed URLs prevent abuse
- **Scalability:** Object storage handles 22.5 PB without app server scaling

**Production Enhancement:**
- Use AWS S3 presigned URLs (`AmazonS3.generatePresignedUrl()` with 15-min expiry)
- CloudFront CDN for global edge delivery
- Thumbnail generation via S3 Lambda trigger (generate 150px, 640px, 1080px on upload complete)

### 3. Scaling to 500M DAU

#### Application Server Scaling (Stateless Horizontal)
```
Load:
  - Feed reads: 173K QPS (peak: 300K QPS)
  - Post writes: 1157 QPS
  - Follow ops: 289 QPS

Strategy:
  - Stateless Spring Boot services behind load balancer
  - Auto-scaling based on CPU/RPS metrics
  - Estimated: 500 app servers @ 500 RPS each = 250K RPS capacity
  - Over-provision for peak: 1000 servers = 500K RPS
```

#### Redis Cluster (Feed Cache)
```
Workload:
  - 231K writes/sec (fanout-on-write)
  - 173K reads/sec (feed fetches)
  - 2.4 TB working set (100M users × 1000 posts × 24 bytes)

Strategy:
  - Redis Cluster with 50 shards (hash slot partitioning)
  - Shard key: userId hash (feed data co-located)
  - Each shard: 48 GB RAM, ~5K writes/sec, ~4K reads/sec
  - Replication: 2 replicas per shard (read scaling + failover)
  - Total nodes: 50 masters + 100 replicas = 150 nodes
```

#### Database Sharding (Users + Posts + Follows)
```
Problem:
  - 9 TB metadata (users, posts, follows, media)
  - 190K read QPS, 1500 write QPS
  - Single PostgreSQL instance limit: ~10K QPS

Strategy (Shard by userId):
  - 64 database shards (hash userId % 64)
  - Each shard: ~150 GB data, ~3K QPS
  - Routing layer: application computes shard ID from userId
  - Cross-shard queries (feed read): scatter-gather to all followed users' shards, merge results
  - Write: single-shard (follow, post creation)

Challenges:
  - Celebrity posts cross shard boundaries (solved by fanout-on-read)
  - Feed merge requires querying multiple shards (use Redis cache to avoid DB hits)
```

#### CDN / Object Storage (Media Blobs)
```
Storage: 22.5 PB (30-day retention)
Bandwidth: 222 Tbps download (peak)

Strategy:
  - S3 for blob storage (automatic replication, 11 nines durability)
  - CloudFront CDN (edge locations in 200+ cities)
  - Cache hit ratio: ~95% (popular posts cached at edge)
  - Origin fetch: 5% × 222 Tbps = 11 Tbps from S3 (acceptable)
```

### 4. Cold-Start & Feed Consistency

#### Cold-Start Problem
**Scenario:** New user or Redis cache miss (eviction, failure, new follower)  
**Challenge:** Redis feed is empty, cannot serve feed

**Solution (DB Fallback):**
```java
if (precomputedPostIds.isEmpty()) {
    log.debug("Cold start for user {}: fetching from DB", userId);
    fallbackPosts = postRepository.findByAuthorIdInOrderByCreatedAtDesc(
        followeeIds, PageRequest.of(0, (page + 1) * pageSize + bufferSize)
    );
}
```
- Query DB for recent posts from all followed users
- Merge with celebrity posts, deduplicate, sort, paginate
- Return feed (no Redis writes during cold-start read)
- **Next post from followee:** FanoutService will populate Redis feed (exits cold-start)

**Why Not Pre-Warm Cache?**
- 500M users × 1000 posts × 24 bytes = 12 TB (exceeds Redis capacity)
- Only 100M active users (20%) need cached feeds
- Cold-start is rare (new users, cache eviction) and acceptable latency (~200ms DB query)

#### Feed Consistency Guarantees

**Normal Users (Fanout-on-Write):**
- **Strong consistency:** FanoutService pushes post to Redis immediately after creation
- Feed reflects post within ~50ms (async fanout latency)
- **Trade-off:** Slightly stale during fanout propagation (acceptable for social feed)

**Celebrity Followers (Fanout-on-Read):**
- **Eventual consistency:** FeedService queries DB for recent celebrity posts (last 24h)
- Guaranteed to see celebrity post within 1 DB replication lag (~seconds)
- **Trade-off:** Extra DB query latency (~50ms), but necessary to avoid write storm

**Missing Post Scenarios:**
1. **Redis eviction (memory pressure):** Cold-start fallback queries DB
2. **New follower:** No pre-populated feed; cold-start on first read
3. **Celebrity post:** Not in Redis; fetched via fanout-on-read DB query
4. **Post creation in-flight:** Async fanout not yet complete; next feed refresh shows it

**Consistency vs Latency Trade-Off:**
- Prioritize **latency** (p99 < 500ms) over **perfect consistency**
- Social feed tolerates seconds-level staleness (users don't notice)
- Critical: no duplicate posts (achieved via dedupe in FeedService.merge)

## Trade-Offs & Alternatives

### Hybrid Fanout vs Pure Fanout-on-Write
**Chosen:** Hybrid (write for normal, read for celebrity)  
**Alternative:** Pure fanout-on-write (push to all followers always)  
**Trade-Off:**  
- Pure fanout-on-write: simpler code, but celebrity posts create 10M+ Redis writes (hotspot, write storm)
- Hybrid: adds complexity (celebrity detection, DB query on read), but avoids write amplification

### Hybrid Fanout vs Pure Fanout-on-Read
**Chosen:** Hybrid  
**Alternative:** Pure fanout-on-read (query DB on every feed read)  
**Trade-Off:**  
- Pure fanout-on-read: no Redis writes, no write amplification, simple architecture
- But: 173K feed QPS × DB query latency = p99 > 500ms (violates NFR)
- Hybrid: Redis cache absorbs 80%+ of feed reads, meets latency target

### Presigned URLs vs Direct Upload via App Server
**Chosen:** Presigned URLs (client → S3 direct)  
**Alternative:** Client → App Server → S3 (proxy upload)  
**Trade-Off:**  
- Direct upload: offloads 69 Gbps from app servers, reduces latency (edge upload)
- Proxy: simpler client code, but app servers become bandwidth bottleneck

### Denormalized Counts vs Computed Aggregates
**Chosen:** Denormalized (followerCount, followingCount, postCount in User table)  
**Alternative:** `COUNT(*)` on Follows/Posts tables on every profile view  
**Trade-Off:**  
- Denormalized: instant profile loads, no query cost
- Computed: always accurate, but ~50ms query latency × 17K profile QPS = 850K DB queries/sec (unacceptable)

### Redis ZSET vs Relational Feed Table
**Chosen:** Redis ZSET (sorted set with score=timestamp)  
**Alternative:** PostgreSQL `feeds` table (userId, postId, timestamp)  
**Trade-Off:**  
- Redis ZSET: O(log N) insert, O(1) range fetch, sub-ms latency
- PostgreSQL: requires index on (userId, timestamp), slower for 173K QPS reads

### Celebrity Threshold (100K Followers)
**Chosen:** 100K (configurable via `app.feed.celebrity-follower-threshold`)  
**Alternative:** 1M followers (fewer celebrities)  
**Trade-Off:**  
- 100K: catches power users early (reduce write load)
- 1M: more users get instant fanout-on-write, but ~1000 users with 100K–1M followers still cause write spikes

### Celebrity Lookback Window (24 Hours)
**Chosen:** 24 hours (configurable via `app.feed.celebrity-lookback-hours`)  
**Alternative:** 7 days (more posts in feed)  
**Trade-Off:**  
- 24h: bounded DB query size (~50 posts per celebrity), fast query
- 7 days: users see older celebrity posts, but DB query returns 350 posts × 10 celebrities = 3500 rows (slower)

## Technology Stack

- **Framework:** Spring Boot 3.2.0
- **Language:** Java 17
- **Database:** H2 (in-memory, dev) / PostgreSQL (production)
- **ORM:** Spring Data JPA + Hibernate
- **Cache:** Redis 7 (Spring Data Redis)
- **Async:** Spring `@Async` with custom thread pool (`fanoutExecutor`)
- **Object Storage / CDN:** Simulated (S3 + CloudFront in production)
- **Build Tool:** Maven
- **Utilities:** Lombok (reduce boilerplate)

## Getting Started

### Prerequisites
- Java 17+
- Maven 3.8+
- Redis 7+ (required for feed caching; app will log errors but degrade gracefully without it)

### Running Locally

1. **Start Redis**
```bash
docker run -d -p 6379:6379 redis:alpine

# Verify Redis is running
redis-cli ping
# Expected output: PONG
```

2. **Build and Run**
```bash
cd instagram
mvn clean install
mvn spring-boot:run
```

3. **Application starts on port 8080**
- H2 console: http://localhost:8080/h2-console (JDBC URL: `jdbc:h2:mem:instagram`, username: `sa`, password: empty)
- Sample data is pre-loaded by `DataInitializer` (disabled in test profile)

### Quick Test

#### 1. Create Users
```bash
# Create user1
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","displayName":"Alice Smith","email":"alice@example.com","bio":"Travel photographer"}'

# Save the returned user ID as USER1_ID
USER1_ID="<paste-id-here>"

# Create user2
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","displayName":"Bob Jones","email":"bob@example.com","bio":"Food blogger"}'

# Save the returned user ID as USER2_ID
USER2_ID="<paste-id-here>"
```

#### 2. Follow User
```bash
# User1 follows User2
curl -X POST "http://localhost:8080/api/users/${USER1_ID}/follow?targetId=${USER2_ID}"
```

#### 3. Upload Media & Create Post
```bash
# Request upload URL
UPLOAD_RESPONSE=$(curl -X POST http://localhost:8080/api/media/upload-url \
  -H "Content-Type: application/json" \
  -d "{\"uploaderId\":\"${USER2_ID}\",\"type\":\"PHOTO\",\"sizeBytes\":3145728}")

echo $UPLOAD_RESPONSE

# Extract mediaId (use jq or manual copy)
MEDIA_ID=$(echo $UPLOAD_RESPONSE | jq -r '.mediaId')

# Mark upload as complete (simulate client uploaded to presigned URL)
curl -X POST "http://localhost:8080/api/media/${MEDIA_ID}/complete"

# Create post with uploaded media
curl -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d "{\"authorId\":\"${USER2_ID}\",\"caption\":\"Beautiful sunset at the beach! #travel\",\"mediaType\":\"PHOTO\",\"mediaIds\":[\"${MEDIA_ID}\"]}"
```

#### 4. Get Feed (User1 sees User2's post)
```bash
# User1's feed should show User2's post
curl "http://localhost:8080/api/feed?userId=${USER1_ID}&page=0&pageSize=20" | jq .

# Response shows:
# {
#   "userId": "...",
#   "posts": [ { "id": "...", "authorUsername": "bob", "caption": "Beautiful sunset...", ... } ],
#   "page": 0,
#   "pageSize": 20,
#   "hasMore": false,
#   "feedStrategyNote": "FANOUT_ON_WRITE (precomputed: 1 post IDs from Redis)"
# }
```

#### 5. Test Celebrity Behavior
```bash
# Promote User2 to celebrity (manually set followerCount >= 100K)
# This requires direct DB access or updating via H2 console:
# UPDATE users SET follower_count = 100000, is_celebrity = true WHERE id = '${USER2_ID}';

# Create another post from User2 (now celebrity)
# Fanout will be SKIPPED (check logs for "Skipping fanout for celebrity post")

# User1's feed will now show:
# "feedStrategyNote": "FANOUT_ON_READ (celebrity: 1 posts from 1 celebrities, last 24h)"
```

## Testing

```bash
mvn test
```

**Test Coverage (61 tests):**
- **FeedServiceTest:** Hybrid fanout strategy, cold-start fallback, celebrity merging, pagination
- **FanoutServiceTest:** Celebrity detection, Redis push, async execution
- **PostServiceTest:** Post creation, media validation, CDN URL resolution
- **MediaServiceTest:** Presigned URL generation, size validation, upload completion
- **FollowServiceTest:** Follow/unfollow, count updates, celebrity promotion/demotion
- **Integration Tests:** End-to-end feed scenarios with multiple users and posts

## Learning Objectives

1. **Hybrid Fan-Out Architecture:** Balance write amplification (fanout-on-write) with read latency (fanout-on-read) based on follower count
2. **Celebrity Problem:** Identify high-follower accounts and switch to pull-based feed generation to avoid write storms
3. **Presigned URLs:** Offload media upload bandwidth from app servers; leverage CDN for global delivery
4. **Cache-Aside Pattern:** Redis as first-tier cache with DB fallback (cold-start)
5. **Denormalization:** Precompute follower/following/post counts for instant profile loads
6. **Async Processing:** Non-blocking fanout via `@Async` to avoid blocking post creation
7. **Feed Consistency Trade-Offs:** Eventual consistency in social feeds is acceptable; optimize for latency over perfect ordering
8. **Capacity Estimation:** Derive storage, bandwidth, and QPS requirements from DAU and post volume

## References
- [System Design Interview — Instagram](https://www.hellointerview.com/learn/system-design/problem-breakdowns/instagram)
- [Designing Instagram — Grokking the System Design Interview](https://www.designgurus.io/course-play/grokking-the-system-design-interview/doc/638c0b5aac93e7ae59a1af6a)
- [AWS S3 Presigned URLs](https://docs.aws.amazon.com/AmazonS3/latest/userguide/PresignedUrlUploadObject.html)
- [Redis Sorted Sets (ZSET)](https://redis.io/docs/data-types/sorted-sets/)

## License

Educational system design project. Not for production use.
