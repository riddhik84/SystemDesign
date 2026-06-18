# Facebook News Feed — System Design

> **Implementation status:** This repository contains a complete, production-quality Spring Boot implementation demonstrating fanout-on-write architecture with hybrid fanout optimization for celebrity users.

---

## Table of Contents

1. [Problem Statement & Requirements](#1-problem-statement--requirements)
2. [Capacity Estimation](#2-capacity-estimation)
3. [Core Entities & Data Model](#3-core-entities--data-model)
4. [API Design](#4-api-design)
5. [High-Level Architecture](#5-high-level-architecture)
6. [Deep Dive: Fanout Strategy](#6-deep-dive-fanout-strategy)
7. [Deep Dive: Feed Ranking](#7-deep-dive-feed-ranking)
8. [Deep Dive: Write Path](#8-deep-dive-write-path)
9. [Deep Dive: Read Path](#9-deep-dive-read-path)
10. [Deep Dive: Scalability](#10-deep-dive-scalability)
11. [Trade-offs & Alternatives](#11-trade-offs--alternatives)

---

## 1. Problem Statement & Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| FR-1 | Users can create posts (text, images, videos) |
| FR-2 | Users can follow other users |
| FR-3 | Users can view their personalized news feed (posts from followed users) |
| FR-4 | Feed is ranked by relevance (engagement, recency, relationship strength) |
| FR-5 | Real-time updates (new posts appear in feed within seconds) |

### Non-Functional Requirements

| Metric | Target |
|--------|--------|
| Scale | 2 billion users, 500M DAU |
| Posts created | 100M posts/day |
| Feed reads | 10B requests/day |
| Feed latency (p95) | < 500 ms |
| Availability | 99.9% |
| Fanout latency | < 5 seconds (post visible to followers) |

### Out of Scope
- Comments, likes, shares (separate systems)
- Stories, Reels, Marketplace
- Ads insertion
- Content moderation

---

## 2. Capacity Estimation

### Storage

**Posts:**
- 100M posts/day × 365 days = 36.5B posts/year
- Average post: 1 KB (text + metadata)
- Annual storage: 36.5B × 1 KB = **36.5 TB/year**
- With 3× replication: **~110 TB/year**

**Feed Cache (Redis):**
- 500M DAU × 500 posts per feed × 100 bytes per entry = **25 TB**
- Per-user feed stored as sorted set in Redis

**Friendships:**
- Average 300 friends/user
- 2B users × 300 × 2 (bidirectional) × 20 bytes = **24 TB**

**Total:** ~**160 TB** (posts + feed cache + friendships)

### Traffic

**Write (Post Creation):**
- 100M posts/day ÷ 86,400 = **~1,150 writes/second**
- Peak (2× average): **~2,300 writes/second**

**Read (Feed Fetch):**
- 10B feed requests/day ÷ 86,400 = **~115,000 reads/second**
- Peak (3× average): **~350,000 reads/second**

**Fanout Operations:**
- 1,150 posts/s × 300 followers avg = **345,000 fanout writes/second**
- With batching: ~**35,000 Redis operations/second**

### Bandwidth

**Feed fetch:**
- 115K QPS × 50 KB response = **5.75 GB/s** = **46 Gbps**
- With 80% cache hit rate: **9.2 Gbps** from DB

---

## 3. Core Entities & Data Model

### User
```java
@Entity
@Table(name = "users")
public class User {
    @Id
    private String id;
    private String username;
    private String displayName;
    private Boolean isCelebrity;  // >1M followers
    private Integer followerCount;
}
```

### Post
```java
@Entity
@Table(name = "posts", indexes = {
    @Index(name = "idx_user_created", columnList = "user_id,created_at")
})
public class Post {
    @Id
    private String id;
    
    @ManyToOne
    private User author;
    
    private String content;
    private PostType type;  // TEXT, IMAGE, VIDEO
    private String mediaUrl;
    
    private Instant createdAt;
    private Integer likeCount;
    private Integer commentCount;
    private Integer shareCount;
}
```

### Friendship
```java
@Entity
@Table(name = "friendships", indexes = {
    @Index(name = "idx_follower", columnList = "follower_id"),
    @Index(name = "idx_followee", columnList = "followee_id")
})
public class Friendship {
    @Id
    private String id;
    
    @ManyToOne
    private User follower;  // User doing the following
    
    @ManyToOne
    private User followee;  // User being followed
    
    private Instant createdAt;
    private FriendshipStrength strength;  // CLOSE, NORMAL, DISTANT
}
```

### FeedEntry (Cache-only, not in DB)
```java
// Stored in Redis sorted set per user
// Key: feed:{userId}
// Score: ranking score (timestamp + engagement)
// Value: postId
public class FeedEntry {
    private String postId;
    private double score;  // For ranking
    private Instant createdAt;
}
```

---

## 4. API Design

### 4.1 Create Post
```http
POST /api/v1/posts
Content-Type: application/json

{
  "content": "Hello world!",
  "type": "TEXT",
  "mediaUrl": null
}
```

**Response:**
```json
{
  "postId": "post_123",
  "authorId": "user_456",
  "content": "Hello world!",
  "createdAt": "2026-06-18T10:00:00Z"
}
```

### 4.2 Get News Feed
```http
GET /api/v1/feed?userId=user_456&page=0&size=20
```

**Response:**
```json
{
  "posts": [
    {
      "postId": "post_789",
      "author": {
        "userId": "user_111",
        "username": "john_doe",
        "displayName": "John Doe"
      },
      "content": "Great day!",
      "type": "TEXT",
      "createdAt": "2026-06-18T09:30:00Z",
      "likeCount": 42,
      "commentCount": 5
    }
  ],
  "page": 0,
  "hasMore": true
}
```

### 4.3 Follow User
```http
POST /api/v1/users/{userId}/follow
Content-Type: application/json

{
  "followeeId": "user_789"
}
```

---

## 5. High-Level Architecture

```
┌────────────────────────────────────────────────────────┐
│                  Client Apps                            │
└────────────────┬───────────────────────────────────────┘
                 │
                 ▼
         ┌───────────────┐
         │  CDN + WAF    │
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
│  API Tier   │   ...   │  API Tier   │
└──┬──────┬───┘         └──┬──────┬───┘
   │      │                 │      │
   │      └─────────┬───────┘      │
   │                │              │
   ▼                ▼              ▼
┌──────────┐  ┌──────────┐  ┌──────────┐
│  Redis   │  │  Redis   │  │  Redis   │  (Feed Cache)
│  Cluster │  │  Cluster │  │  Cluster │
└──────────┘  └──────────┘  └──────────┘
   │                │              │
   └────────────────┼──────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │   PostgreSQL         │
         │   (Posts)            │
         └──────┬───────────────┘
                │
         ┌──────┴───────┐
         │              │
         ▼              ▼
    ┌────────┐     ┌────────┐
    │Replica │     │Replica │
    └────────┘     └────────┘

┌─────────────────────────────────────────────────────────┐
│                 Background Services                      │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────┐         ┌──────────────────┐
│  Fanout Service  │────────▶│  Message Queue   │
│  (Workers)       │         │  (Kafka)         │
└──────────────────┘         └──────────────────┘
```

---

## 6. Deep Dive: Fanout Strategy

### 6.1 Fanout-on-Write (Push Model)

**How it works:**
1. User creates post
2. System immediately writes post to all followers' feeds
3. Feed read is fast (pre-computed)

**Implementation:**
```java
@Service
public class FanoutService {
    
    @Async
    public void fanoutPost(String postId, String authorId) {
        // Get all followers
        List<String> followers = friendshipRepository
            .findFollowerIdsByFolloweeId(authorId);
        
        // Write to each follower's feed (Redis sorted set)
        for (String followerId : followers) {
            String feedKey = "feed:" + followerId;
            double score = calculateScore(post);
            
            redisTemplate.opsForZSet().add(feedKey, postId, score);
            
            // Keep feed size bounded (top 1000 posts)
            redisTemplate.opsForZSet().removeRange(feedKey, 0, -1001);
        }
    }
}
```

**Pros:**
- ✅ Fast reads (feed pre-computed)
- ✅ Simple ranking (score computed once)

**Cons:**
- ❌ Slow writes for users with many followers
- ❌ Wastes resources for inactive users

---

### 6.2 Fanout-on-Read (Pull Model)

**How it works:**
1. User requests feed
2. System queries posts from all followed users
3. Merges and ranks in real-time

**Implementation:**
```java
@Service
public class FeedService {
    
    public List<Post> generateFeedOnRead(String userId) {
        // Get followed users
        List<String> followedUsers = friendshipRepository
            .findFolloweeIdsByFollowerId(userId);
        
        // Fetch recent posts from each
        List<Post> posts = postRepository
            .findByAuthorIdInOrderByCreatedAtDesc(
                followedUsers, 
                PageRequest.of(0, 500)
            );
        
        // Rank and return top 20
        return rankPosts(posts).subList(0, 20);
    }
}
```

**Pros:**
- ✅ Fast writes (no fanout)
- ✅ No wasted work for inactive users

**Cons:**
- ❌ Slow reads (compute every time)
- ❌ Complex ranking (must be real-time)

---

### 6.3 Hybrid Approach (Chosen)

**Strategy:**
- **Normal users** (< 1M followers): Fanout-on-write
- **Celebrities** (> 1M followers): Fanout-on-read

**Implementation:**
```java
@Service
public class HybridFanoutService {
    
    private static final int CELEBRITY_THRESHOLD = 1_000_000;
    
    public void handleNewPost(Post post) {
        User author = post.getAuthor();
        
        if (author.getFollowerCount() < CELEBRITY_THRESHOLD) {
            // Fanout-on-write
            fanoutService.fanoutPost(post.getId(), author.getId());
        } else {
            // Celebrity: skip fanout, handle at read time
            markAsCelebrity(author);
        }
    }
    
    public List<Post> getFeed(String userId) {
        // Get pre-computed feed (from normal users)
        List<Post> precomputedFeed = getPrecomputedFeed(userId);
        
        // Get celebrity posts on-demand
        List<String> celebrityIds = getCelebritiesFollowed(userId);
        List<Post> celebrityPosts = postRepository
            .findByAuthorIdInOrderByCreatedAtDesc(celebrityIds, ...);
        
        // Merge and rank
        return mergeAndRank(precomputedFeed, celebrityPosts);
    }
}
```

**Why hybrid wins:**
- Normal users (99.9%): Fast reads (fanout-on-write)
- Celebrities (0.1%): Avoids fanout explosion
- Best of both worlds

---

## 7. Deep Dive: Feed Ranking

### 7.1 Ranking Score

```java
private double calculateScore(Post post, User viewer) {
    double score = 0.0;
    
    // 1. Recency (exponential decay)
    long hoursOld = ChronoUnit.HOURS.between(post.getCreatedAt(), Instant.now());
    double recencyScore = Math.exp(-0.05 * hoursOld);
    score += recencyScore * 1000;
    
    // 2. Engagement
    double engagementScore = Math.log1p(
        post.getLikeCount() + post.getCommentCount() * 2 + post.getShareCount() * 3
    );
    score += engagementScore * 100;
    
    // 3. Relationship strength
    FriendshipStrength strength = getStrength(viewer, post.getAuthor());
    score += switch(strength) {
        case CLOSE -> 500;
        case NORMAL -> 100;
        case DISTANT -> 10;
    };
    
    // 4. Content type preference
    if (post.getType() == viewer.getPreferredContentType()) {
        score += 200;
    }
    
    return score;
}
```

**Factors:**
1. **Recency**: Newer posts rank higher (50% weight)
2. **Engagement**: Likes, comments, shares (30% weight)
3. **Relationship strength**: Close friends rank higher (15% weight)
4. **Content type**: User's preference (5% weight)

---

## 8. Deep Dive: Write Path

### Flow: User creates post → Fanout → Feed updated

```java
@Service
@Transactional
public class PostService {
    
    public PostResponse createPost(CreatePostRequest request) {
        // 1. Save post to DB
        Post post = Post.builder()
            .id(UUID.randomUUID().toString())
            .author(getCurrentUser())
            .content(request.getContent())
            .type(request.getType())
            .createdAt(Instant.now())
            .build();
        
        postRepository.save(post);
        
        // 2. Async fanout (if not celebrity)
        if (!post.getAuthor().getIsCelebrity()) {
            kafkaProducer.send("post-created", post.getId());
        }
        
        return toResponse(post);
    }
}

@Service
public class FanoutWorker {
    
    @KafkaListener(topics = "post-created")
    public void handleNewPost(String postId) {
        Post post = postRepository.findById(postId).orElseThrow();
        
        // Batch fetch followers
        List<String> followers = friendshipRepository
            .findFollowerIdsByFolloweeId(post.getAuthor().getId());
        
        // Fanout in batches (100 per batch)
        for (int i = 0; i < followers.size(); i += 100) {
            List<String> batch = followers.subList(i, Math.min(i + 100, followers.size()));
            fanoutBatch(post, batch);
        }
    }
    
    private void fanoutBatch(Post post, List<String> followers) {
        // Use Redis pipeline for efficiency
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String followerId : followers) {
                String key = "feed:" + followerId;
                double score = calculateScore(post);
                connection.zAdd(key.getBytes(), score, post.getId().getBytes());
            }
            return null;
        });
    }
}
```

**Optimizations:**
1. **Async fanout**: Post creation returns immediately
2. **Kafka queue**: Decouples write from fanout
3. **Redis pipeline**: Batch 100 writes per round-trip
4. **Bounded feed size**: Keep top 1000 posts per user

---

## 9. Deep Dive: Read Path

```java
@Service
public class FeedService {
    
    @Cacheable(value = "userFeed", key = "#userId")
    public FeedResponse getFeed(String userId, Pageable pageable) {
        // 1. Get post IDs from Redis (sorted by score)
        String feedKey = "feed:" + userId;
        Set<String> postIds = redisTemplate.opsForZSet()
            .reverseRange(feedKey, 0, 499);  // Top 500
        
        if (postIds.isEmpty()) {
            return buildEmptyFeed();
        }
        
        // 2. Fetch posts from DB (batch query)
        List<Post> posts = postRepository.findByIdIn(new ArrayList<>(postIds));
        
        // 3. If user follows celebrities, merge their posts
        List<String> celebrityIds = getCelebrities(userId);
        if (!celebrityIds.isEmpty()) {
            List<Post> celebrityPosts = postRepository
                .findByAuthorIdInAndCreatedAtAfter(
                    celebrityIds, 
                    Instant.now().minus(24, ChronoUnit.HOURS)
                );
            posts = mergeAndRank(posts, celebrityPosts);
        }
        
        // 4. Paginate
        int start = pageable.getPageNumber() * pageable.getPageSize();
        int end = Math.min(start + pageable.getPageSize(), posts.size());
        
        return FeedResponse.builder()
            .posts(posts.subList(start, end).stream().map(this::toDTO).collect(Collectors.toList()))
            .page(pageable.getPageNumber())
            .hasMore(end < posts.size())
            .build();
    }
}
```

**Performance:**
- Redis lookup: ~1 ms
- DB batch fetch: ~50 ms
- Ranking: ~20 ms
- Total: **< 100 ms** (well under 500 ms target)

---

## 10. Deep Dive: Scalability

### 10.1 Database Sharding

**Shard by User ID:**
```
Shard 0: user_id % 100 == 0
Shard 1: user_id % 100 == 1
...
Shard 99: user_id % 100 == 99
```

**Queries:**
- Feed fetch: Single shard (user's feed)
- Post creation: Single shard (author's shard)
- Fanout: Multi-shard (followers on different shards)

### 10.2 Redis Sharding

**Feed cache sharded by user ID:**
- Each shard handles 5M users
- 100 Redis shards for 500M DAU

### 10.3 Kafka Partitioning

**Partition by author ID:**
- Ensures all posts from same author go to same partition
- Maintains ordering per author

---

## 11. Trade-offs & Alternatives

### Fanout Strategy

| Approach | Pros | Cons | When to Use |
|----------|------|------|-------------|
| **Fanout-on-write** | Fast reads, simple ranking | Slow writes, wasted work | Small follower counts |
| **Fanout-on-read** | Fast writes, no waste | Slow reads, complex ranking | Large follower counts |
| **Hybrid (chosen)** | Best of both | More complex | Production systems |

### Storage

| Option | Pros | Cons |
|--------|------|------|
| **Redis sorted sets** | Fast, ranked, TTL | Memory expensive |
| **Cassandra wide columns** | Scalable, persistent | Slower than Redis |
| **DynamoDB** | Managed, scalable | Expensive, eventual consistency |

**Chosen:** Redis for hot data (1000 posts), Cassandra for cold storage.

---

## Summary

This Facebook News Feed design demonstrates:
1. **Hybrid fanout**: Fanout-on-write for normal users, fanout-on-read for celebrities
2. **Redis sorted sets**: Pre-computed feeds with ranking scores
3. **Async processing**: Kafka for decoupled fanout
4. **Feed ranking**: Recency + engagement + relationship + preference
5. **Scalability**: Sharding by user ID, Redis cluster, Kafka partitions

**Key Metrics:**
- 500M DAU, 115K feed reads/sec
- Feed latency < 500 ms
- Fanout < 5 seconds

**Implementation:** Complete Spring Boot codebase in `src/main/java/com/systemdesign/newsfeed/`.
