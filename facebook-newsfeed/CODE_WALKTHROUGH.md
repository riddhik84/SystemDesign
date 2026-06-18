# Facebook News Feed — Code Walkthrough

## Core Architecture: Hybrid Fanout

**Write Path:** Post → Kafka → Fanout Worker → Redis  
**Read Path:** Redis sorted set → Post IDs → DB batch fetch → Ranked list

## Key Implementation

### Hybrid Fanout Decision
```java
public void handleNewPost(Post post) {
    if (post.getAuthor().getFollowerCount() < 1_000_000) {
        // Normal user: fanout-on-write
        kafkaProducer.send("post-created", post.getId());
    } else {
        // Celebrity: skip fanout (fetch at read time)
        markAsCelebrity(post.getAuthor());
    }
}
```

### Fanout Worker (Kafka Consumer)
```java
@KafkaListener(topics = "post-created")
public void fanoutPost(String postId) {
    Post post = postRepository.findById(postId).orElseThrow();
    List<String> followers = friendshipRepo.findFollowers(post.getAuthor().getId());
    
    // Batch write to Redis (100 per pipeline)
    for (List<String> batch : partition(followers, 100)) {
        redisTemplate.executePipelined(connection -> {
            for (String followerId : batch) {
                connection.zAdd(("feed:" + followerId).getBytes(), 
                               calculateScore(post), 
                               postId.getBytes());
            }
            return null;
        });
    }
}
```

### Feed Generation (Read)
```java
public FeedResponse getFeed(String userId) {
    // 1. Get post IDs from Redis sorted set (pre-computed)
    Set<String> postIds = redisTemplate.opsForZSet()
        .reverseRange("feed:" + userId, 0, 499);
    
    // 2. Fetch posts from DB
    List<Post> posts = postRepository.findByIdIn(postIds);
    
    // 3. Merge celebrity posts (fanout-on-read)
    List<String> celebs = getCelebritiesFollowed(userId);
    List<Post> celebPosts = postRepository.findRecent(celebs);
    
    // 4. Merge, rank, paginate
    return mergeAndRank(posts, celebPosts);
}
```

### Ranking Score Calculation
```java
private double calculateScore(Post post) {
    long hoursOld = hoursOld(post.getCreatedAt());
    double recency = Math.exp(-0.05 * hoursOld);  // Decay
    double engagement = Math.log1p(post.getLikeCount() + post.getCommentCount() * 2);
    return recency * 1000 + engagement * 100;
}
```

## Data Flow

**Post Creation:**
1. User creates post → `POST /api/v1/posts`
2. Save to PostgreSQL
3. Send to Kafka topic `post-created`
4. Fanout worker consumes, writes to followers' Redis feeds
5. Each follower's feed updated within 5 seconds

**Feed Fetch:**
1. User requests feed → `GET /api/v1/feed?userId=123`
2. Redis lookup: `feed:123` → get top 500 post IDs (sorted by score)
3. DB batch query: `SELECT * FROM posts WHERE id IN (...)`
4. If user follows celebrities, fetch their recent posts separately
5. Merge, rank, return top 20

## Performance
- **Write:** < 50 ms (post saved, Kafka queued)
- **Read:** < 100 ms (Redis + DB batch fetch)
- **Fanout:** < 5 sec (async via Kafka)

## Key Files
- `FanoutService.java` - Hybrid fanout logic
- `FeedService.java` - Feed generation (Redis + DB)
- `PostService.java` - Post creation
- `RankingService.java` - Score calculation
