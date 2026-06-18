# Tinder Dating App — System Design

> **Implementation status:** Production-quality Spring Boot implementation demonstrating geospatial indexing with Redis Geohash for location-based matching.

---

## Table of Contents

1. [Problem Statement & Requirements](#1-problem-statement--requirements)
2. [Capacity Estimation](#2-capacity-estimation)
3. [Core Entities & Data Model](#3-core-entities--data-model)
4. [API Design](#4-api-design)
5. [High-Level Architecture](#5-high-level-architecture)
6. [Deep Dive: Geospatial Matching](#6-deep-dive-geospatial-matching)
7. [Deep Dive: Recommendation Algorithm](#7-deep-dive-recommendation-algorithm)
8. [Deep Dive: Match Detection](#8-deep-dive-match-detection)
9. [Deep Dive: Real-Time Messaging](#9-deep-dive-real-time-messaging)
10. [Trade-offs & Alternatives](#10-trade-offs--alternatives)

---

## 1. Problem Statement & Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| FR-1 | Users create profile (photos, bio, preferences: age, distance, gender) |
| FR-2 | Users discover nearby profiles within specified radius (1-100 miles) |
| FR-3 | Users swipe right (like) or left (pass) on profiles |
| FR-4 | System detects mutual likes and creates "match" |
| FR-5 | Matched users can chat in real-time |

### Non-Functional Requirements

| Metric | Target |
|--------|--------|
| Scale | 75M users, 10M DAU |
| Profile discovery | 50M requests/day (~580 QPS) |
| Swipes | 1.6B swipes/day (~18K writes/sec) |
| Matches | 20M matches/day |
| Chat messages | 500M messages/day |
| Discovery latency (p95) | < 500 ms |
| Geospatial query | < 100 ms |

### Out of Scope
- Payment/subscriptions (Tinder Plus, Gold)
- Super Likes, Boosts
- Video chat
- Safety features (report, block)

---

## 2. Capacity Estimation

### Storage

**User Profiles:**
- 75M users × 5 KB (bio + metadata) = **375 GB**
- Photos: 75M × 6 photos × 500 KB = **225 TB** (stored in S3, URLs in DB)

**Swipes:**
- 1.6B swipes/day × 365 days = 584B swipes/year
- Each swipe: 40 bytes (user_id, target_id, direction, timestamp)
- Annual: 584B × 40 = **23.4 TB/year**
- With indexing: **~70 TB/year**

**Matches:**
- 20M matches/day × 365 = 7.3B matches/year
- Each match: 50 bytes
- Annual: **365 GB/year**

**Messages:**
- 500M messages/day × 365 = 182B messages/year
- Each message: 200 bytes avg
- Annual: **36.4 TB/year**

**Total:** ~**130 TB/year** (excluding photos in S3)

### Traffic

**Profile Discovery:**
- 50M requests/day ÷ 86,400 = **~580 QPS**
- Peak (3×): **~1,700 QPS**

**Swipes:**
- 1.6B/day ÷ 86,400 = **~18,500 writes/sec**
- Peak: **~55,000 writes/sec**

**Geospatial Lookups:**
- Every discovery request = 1 geo query
- **~1,700 geo queries/sec** at peak

---

## 3. Core Entities & Data Model

### User
```java
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_location", columnList = "latitude,longitude")
})
public class User {
    @Id
    private String id;
    private String name;
    private Integer age;
    private String bio;
    private Gender gender;
    
    // Location
    private Double latitude;
    private Double longitude;
    private String geohash;  // For efficient spatial indexing
    
    // Preferences
    private Integer minAge;
    private Integer maxAge;
    private Integer maxDistanceMiles;
    private Set<Gender> interestedIn;
    
    // Metadata
    private Boolean isActive;
    private Instant lastActiveAt;
}
```

### Photo
```java
@Entity
@Table(name = "photos")
public class Photo {
    @Id
    private String id;
    
    @ManyToOne
    private User user;
    
    private String s3Url;
    private Integer displayOrder;
}
```

### Swipe
```java
@Entity
@Table(name = "swipes", indexes = {
    @Index(name = "idx_user_target", columnList = "user_id,target_id", unique = true),
    @Index(name = "idx_target_user", columnList = "target_id,user_id")
})
public class Swipe {
    @Id
    private String id;
    
    @ManyToOne
    private User user;      // Swiper
    
    @ManyToOne
    private User target;    // Profile being swiped on
    
    @Enumerated(EnumType.STRING)
    private SwipeDirection direction;  // LIKE, PASS
    
    private Instant createdAt;
}
```

### Match
```java
@Entity
@Table(name = "matches", indexes = {
    @Index(name = "idx_user1", columnList = "user1_id"),
    @Index(name = "idx_user2", columnList = "user2_id")
})
public class Match {
    @Id
    private String id;
    
    @ManyToOne
    private User user1;
    
    @ManyToOne
    private User user2;
    
    private Instant createdAt;
}
```

### Message
```java
@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_match_created", columnList = "match_id,created_at")
})
public class Message {
    @Id
    private String id;
    
    @ManyToOne
    private Match match;
    
    @ManyToOne
    private User sender;
    
    private String content;
    private Instant createdAt;
    private Boolean isRead;
}
```

---

## 4. API Design

### 4.1 Get Recommendations
```http
GET /api/v1/recommendations?userId={userId}&count=10
```

**Response:**
```json
{
  "profiles": [
    {
      "userId": "user_789",
      "name": "Sarah",
      "age": 28,
      "bio": "Love hiking!",
      "photos": ["https://cdn.tinder.com/photo1.jpg"],
      "distanceMiles": 3.2
    }
  ]
}
```

### 4.2 Swipe
```http
POST /api/v1/swipes
Content-Type: application/json

{
  "userId": "user_123",
  "targetId": "user_789",
  "direction": "LIKE"
}
```

**Response:**
```json
{
  "swipeId": "swipe_456",
  "isMatch": true,
  "matchId": "match_999"
}
```

### 4.3 Get Matches
```http
GET /api/v1/matches?userId={userId}
```

**Response:**
```json
{
  "matches": [
    {
      "matchId": "match_999",
      "user": {
        "userId": "user_789",
        "name": "Sarah",
        "photo": "https://cdn.tinder.com/photo1.jpg"
      },
      "lastMessage": "Hey! How's it going?",
      "unreadCount": 2
    }
  ]
}
```

---

## 5. High-Level Architecture

```
┌────────────────────────────────────────────────────────┐
│                  Mobile Apps                            │
│                (iOS, Android)                           │
└────────────────┬───────────────────────────────────────┘
                 │
                 ▼
         ┌───────────────┐
         │  CDN + WAF    │
         └───────┬───────┘
                 │
                 ▼
    ┌────────────────────────────┐
    │   Load Balancer            │
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
│  Redis   │  │  Redis   │  │  Redis   │  (Geo + Cache)
│  Geo     │  │  Cache   │  │  Session │
└──────────┘  └──────────┘  └──────────┘
   │                │
   └────────────────┼──────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │   PostgreSQL         │
         │   (User Sharded)     │
         └──────┬───────────────┘
                │
         ┌──────┴───────┐
         │              │
         ▼              ▼
    ┌────────┐     ┌────────┐
    │Shard 0 │     │Shard 1 │  ...
    └────────┘     └────────┘

┌─────────────────────────────────────────────────────────┐
│              Real-Time Messaging                         │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────┐         ┌──────────────────┐
│  WebSocket       │────────▶│  Message Queue   │
│  Gateway         │         │  (Kafka)         │
└──────────────────┘         └──────────────────┘
```

---

## 6. Deep Dive: Geospatial Matching

### 6.1 Geohash for Location Indexing

**Geohash** encodes lat/lon into a short string. Nearby locations share common prefixes.

**Example:**
- San Francisco: `9q8yy`
- 1 mile away: `9q8yz` (shares `9q8y` prefix)
- 10 miles away: `9q8vu` (shares `9q8` prefix)

**Precision:**
- 5 chars: ~5 km × 5 km
- 6 chars: ~1.2 km × 600 m
- 7 chars: ~150 m × 150 m

### 6.2 Redis Geospatial Commands

**Store user locations:**
```java
redisTemplate.opsForGeo().add(
    "user_locations",
    new Point(longitude, latitude),
    userId
);
```

**Find nearby users:**
```java
GeoResults<GeoLocation<String>> results = redisTemplate.opsForGeo()
    .radius(
        "user_locations",
        new Circle(new Point(userLon, userLat), new Distance(50, Metrics.MILES))
    );
```

**Why Redis Geo?**
- ✅ Sub-100ms queries
- ✅ Built-in radius search
- ✅ Returns distance
- ✅ Scales horizontally

### 6.3 Implementation

```java
@Service
public class GeoService {
    
    private static final String GEO_KEY = "user_locations";
    
    public void updateUserLocation(String userId, double lat, double lon) {
        redisTemplate.opsForGeo().add(GEO_KEY, new Point(lon, lat), userId);
    }
    
    public List<String> findNearbyUsers(
        String userId, 
        double lat, 
        double lon, 
        int radiusMiles,
        int count
    ) {
        // Find users within radius
        GeoResults<GeoLocation<String>> results = redisTemplate.opsForGeo()
            .radius(
                GEO_KEY,
                new Circle(new Point(lon, lat), new Distance(radiusMiles, Metrics.MILES)),
                GeoRadiusCommandArgs.newGeoRadiusArgs().limit(count * 3)
            );
        
        // Filter out self
        return results.getContent().stream()
            .map(result -> result.getContent().getName())
            .filter(id -> !id.equals(userId))
            .limit(count)
            .collect(Collectors.toList());
    }
}
```

---

## 7. Deep Dive: Recommendation Algorithm

### 7.1 Candidate Selection

```java
@Service
public class RecommendationService {
    
    public List<User> getRecommendations(String userId, int count) {
        User user = userRepository.findById(userId).orElseThrow();
        
        // 1. Get nearby users (geospatial)
        List<String> nearbyIds = geoService.findNearbyUsers(
            userId,
            user.getLatitude(),
            user.getLongitude(),
            user.getMaxDistanceMiles(),
            count * 10  // Fetch more candidates
        );
        
        // 2. Fetch full profiles
        List<User> candidates = userRepository.findByIdIn(nearbyIds);
        
        // 3. Filter by preferences
        List<User> filtered = candidates.stream()
            .filter(candidate -> matchesPreferences(user, candidate))
            .filter(candidate -> matchesPreferences(candidate, user))  // Mutual
            .collect(Collectors.toList());
        
        // 4. Filter out already swiped
        Set<String> swipedIds = swipeRepository
            .findTargetIdsByUserId(userId);
        
        filtered = filtered.stream()
            .filter(c -> !swipedIds.contains(c.getId()))
            .collect(Collectors.toList());
        
        // 5. Rank by score
        List<ScoredUser> scored = filtered.stream()
            .map(c -> new ScoredUser(c, calculateScore(user, c)))
            .sorted(Comparator.comparingDouble(ScoredUser::getScore).reversed())
            .collect(Collectors.toList());
        
        return scored.stream()
            .map(ScoredUser::getUser)
            .limit(count)
            .collect(Collectors.toList());
    }
    
    private boolean matchesPreferences(User viewer, User candidate) {
        // Age range
        if (candidate.getAge() < viewer.getMinAge() || 
            candidate.getAge() > viewer.getMaxAge()) {
            return false;
        }
        
        // Gender preference
        if (!viewer.getInterestedIn().contains(candidate.getGender())) {
            return false;
        }
        
        return true;
    }
    
    private double calculateScore(User viewer, User candidate) {
        double score = 0.0;
        
        // Distance penalty (closer = better)
        double distance = calculateDistance(viewer, candidate);
        score += Math.max(0, 100 - distance);  // Max 100 points
        
        // Activity recency
        long hoursInactive = ChronoUnit.HOURS.between(
            candidate.getLastActiveAt(), Instant.now()
        );
        score += Math.max(0, 50 - hoursInactive);  // Active users rank higher
        
        // Profile completeness
        if (candidate.getBio() != null && !candidate.getBio().isEmpty()) {
            score += 20;
        }
        
        return score;
    }
}
```

---

## 8. Deep Dive: Match Detection

### 8.1 The Double-Like Problem

**Scenario:** User A likes User B. Later, User B likes User A → **MATCH**

**Challenge:** Detect mutual like efficiently.

### 8.2 Solution: Check Previous Swipe

```java
@Service
@Transactional
public class SwipeService {
    
    public SwipeResponse swipe(String userId, String targetId, SwipeDirection direction) {
        // 1. Record swipe
        Swipe swipe = Swipe.builder()
            .id(UUID.randomUUID().toString())
            .user(userRepository.getReferenceById(userId))
            .target(userRepository.getReferenceById(targetId))
            .direction(direction)
            .createdAt(Instant.now())
            .build();
        
        swipeRepository.save(swipe);
        
        // 2. If LIKE, check if target already liked user
        if (direction == SwipeDirection.LIKE) {
            Optional<Swipe> reverseSwipe = swipeRepository
                .findByUserIdAndTargetIdAndDirection(targetId, userId, SwipeDirection.LIKE);
            
            if (reverseSwipe.isPresent()) {
                // MATCH!
                Match match = createMatch(userId, targetId);
                return SwipeResponse.builder()
                    .swipeId(swipe.getId())
                    .isMatch(true)
                    .matchId(match.getId())
                    .build();
            }
        }
        
        return SwipeResponse.builder()
            .swipeId(swipe.getId())
            .isMatch(false)
            .build();
    }
    
    private Match createMatch(String user1Id, String user2Id) {
        Match match = Match.builder()
            .id(UUID.randomUUID().toString())
            .user1(userRepository.getReferenceById(user1Id))
            .user2(userRepository.getReferenceById(user2Id))
            .createdAt(Instant.now())
            .build();
        
        matchRepository.save(match);
        
        // Send push notification to both users
        notificationService.sendMatchNotification(user1Id, user2Id);
        
        return match;
    }
}
```

**Query:**
```sql
SELECT * FROM swipes 
WHERE user_id = ? AND target_id = ? AND direction = 'LIKE'
```

**Index:** `(user_id, target_id, direction)` for O(1) lookup.

---

## 9. Deep Dive: Real-Time Messaging

### 9.1 WebSocket for Chat

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").withSockJS();
    }
}

@Controller
public class ChatController {
    
    @MessageMapping("/chat/{matchId}")
    @SendTo("/topic/match/{matchId}")
    public MessageDTO sendMessage(
        @DestinationVariable String matchId,
        MessageRequest request
    ) {
        // Save to DB
        Message message = messageRepository.save(
            Message.builder()
                .match(matchRepository.getReferenceById(matchId))
                .sender(userRepository.getReferenceById(request.getSenderId()))
                .content(request.getContent())
                .createdAt(Instant.now())
                .isRead(false)
                .build()
        );
        
        return toDTO(message);
    }
}
```

**Flow:**
1. Users connect: `ws://api.tinder.com/ws`
2. Subscribe to match: `/topic/match/{matchId}`
3. Send message: `/app/chat/{matchId}`
4. Receive via WebSocket: `/topic/match/{matchId}`

---

## 10. Trade-offs & Alternatives

### Geospatial Indexing

| Approach | Pros | Cons |
|----------|------|------|
| **Redis Geo (chosen)** | Fast (<100ms), built-in radius search | Memory-intensive |
| **PostGIS** | Persistent, SQL queries | Slower (~500ms) |
| **Elasticsearch Geo** | Scalable, full-text + geo | Complex setup |
| **Quadtree** | Custom control | Need to implement from scratch |

### Match Storage

| Approach | Pros | Cons |
|----------|------|------|
| **Separate Match table (chosen)** | Clear schema, easy queries | Extra table |
| **Swipe table with matched flag** | One table | Harder to query matches |
| **Graph database (Neo4j)** | Native relationships | Overkill for this |

### Messaging

| Approach | Pros | Cons |
|----------|------|------|
| **WebSocket (chosen)** | Real-time, low latency | Stateful, complex scaling |
| **Polling** | Simple, stateless | High latency, wasted requests |
| **Server-Sent Events (SSE)** | One-way push, simpler than WS | One-directional |

---

## Summary

This Tinder design demonstrates:
1. **Redis Geospatial**: Sub-100ms radius queries for nearby users
2. **Efficient match detection**: O(1) lookup via indexed swipe table
3. **Smart recommendation**: Distance + activity + profile completeness
4. **Real-time chat**: WebSocket for low-latency messaging
5. **Database sharding**: Shard users by ID for horizontal scaling

**Key Metrics:**
- 10M DAU, 18K swipes/sec
- Geo query < 100 ms
- Discovery < 500 ms

**Implementation:** Complete Spring Boot codebase in `src/main/java/com/systemdesign/tinder/`.
