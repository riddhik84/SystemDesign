# Tinder System Design - Code Walkthrough

This document explains the implementation details for developers new to this codebase.

## Project Structure

```
tinder/
├── src/main/java/com/systemdesign/tinder/
│   ├── TinderApplication.java          # Spring Boot entry point
│   ├── config/                         # Configuration classes
│   │   ├── RedisConfig.java            # Redis cache setup
│   │   └── AsyncConfig.java            # Async thread pool
│   ├── controller/                     # REST API endpoints
│   │   ├── ProfileController.java      # Profile CRUD
│   │   ├── FeedController.java         # Match discovery
│   │   ├── SwipeController.java        # Swipe processing
│   │   └── MatchController.java        # Match retrieval
│   ├── service/                        # Business logic
│   │   ├── ProfileService.java         # Profile management
│   │   ├── FeedService.java            # Feed generation
│   │   ├── SwipeService.java           # Swipe orchestration
│   │   ├── MatchDetectionService.java  # Atomic match detection
│   │   ├── MatchService.java           # Match queries
│   │   └── NotificationService.java    # Push notifications
│   ├── repository/                     # Data access
│   │   ├── UserRepository.java         # User queries
│   │   ├── SwipeRepository.java        # Swipe queries
│   │   └── MatchRepository.java        # Match queries
│   ├── model/                          # Domain entities
│   │   ├── User.java                   # User profile
│   │   ├── Swipe.java                  # Swipe record
│   │   └── Match.java                  # Match record
│   ├── dto/                            # Request/Response objects
│   └── exception/                      # Error handling
└── src/main/resources/
    └── application.yml                 # Configuration
```

---

## Core Components Explained

### 1. Entry Point: TinderApplication.java

```java
@SpringBootApplication
@EnableCaching      // Enables Spring Cache abstraction
@EnableAsync        // Enables @Async annotation support
public class TinderApplication {
    public static void main(String[] args) {
        SpringApplication.run(TinderApplication.class, args);
    }
}
```

**What it does**:
- Bootstraps Spring Boot application
- Enables caching (Redis integration)
- Enables async processing (notification service)

---

### 2. Domain Models

#### User.java (Lines 1-78)

```java
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_location", columnList = "latitude,longitude")
})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    private String name;
    private Integer age;
    private Gender gender;
    private Gender interestedIn;
    private Integer ageMin;
    private Integer ageMax;
    private Integer maxDistance;
    private Double latitude;
    private Double longitude;
    private String bio;
    // ... timestamps, active flag
}
```

**Key Design Decisions**:
- **UUID primary key**: Distributed system friendly, no auto-increment coordination
- **Location index**: Critical for geospatial queries
- **Gender enum**: Type-safe, clear domain model
- **Preferences stored with user**: Denormalized for fast filtering

#### Swipe.java (Lines 1-52)

```java
@Entity
@Table(name = "swipes",
    indexes = {
        @Index(name = "idx_swiper_target", columnList = "swiperId,targetUserId", unique = true),
        @Index(name = "idx_swiper_timestamp", columnList = "swiperId,createdAt")
    }
)
public class Swipe {
    private String swiperId;
    private String targetUserId;
    private Direction direction;  // RIGHT or LEFT
    private LocalDateTime createdAt;
}
```

**Why these indexes**:
1. `(swiperId, targetUserId)` unique: Prevents duplicate swipes
2. `(swiperId, createdAt)`: Fast retrieval of user's swipe history

#### Match.java (Lines 1-43)

```java
@Entity
@Table(name = "matches",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user1Id", "user2Id"})
    }
)
public class Match {
    private String user1Id;  // Always the smaller UUID
    private String user2Id;  // Always the larger UUID
    private LocalDateTime createdAt;
    private Boolean active;
}
```

**Why normalize user pairs**:
- Ensures single match row regardless of who swiped first
- Simplifies queries (don't need OR condition)
- Example: Match between user A and B always stored as `(A, B)` if A < B

---

### 3. Repositories (Data Access Layer)

#### UserRepository.java - Geospatial Query

```java
@Query(value = """
    SELECT u.* FROM users u
    WHERE u.active = true
    AND u.id != :userId
    AND u.age BETWEEN :ageMin AND :ageMax
    AND (:interestedIn = 'NON_BINARY' OR u.gender = CAST(:interestedIn AS text))
    AND u.id NOT IN (
        SELECT s.target_user_id FROM swipes s WHERE s.swiper_id = :userId
    )
    AND (
        6371 * acos(
            cos(radians(:latitude)) * cos(radians(u.latitude)) *
            cos(radians(u.longitude) - radians(:longitude)) +
            sin(radians(:latitude)) * sin(radians(u.latitude))
        )
    ) <= :maxDistance
    ORDER BY RANDOM()
    LIMIT :limit
    """, nativeQuery = true)
List<User> findPotentialMatches(...);
```

**Breaking it down**:

1. **Filters**:
   - `active = true`: Only active users
   - `id != :userId`: Don't show self
   - `age BETWEEN`: Age preference
   - `gender =`: Gender preference
   - `NOT IN (swipes)`: Exclude already swiped
   
2. **Haversine Distance Formula**:
   ```
   distance = R × acos(
       cos(lat1) × cos(lat2) × cos(lon2 - lon1) +
       sin(lat1) × sin(lat2)
   )
   ```
   - R = Earth radius (6371 km)
   - Calculates great-circle distance between two points
   
3. **ORDER BY RANDOM()**: Prevents predictable ordering
4. **LIMIT**: Controls feed size

**Performance Note**: This query is expensive at scale. Production would use Elasticsearch with geo-spatial index.

---

### 4. Service Layer

#### MatchDetectionService.java - The Critical Component

This service solves the **race condition problem** for match detection.

**The Problem**:
```
Time  |  User A                    |  User B
------|----------------------------|---------------------------
T1    | Swipe right on B           |
T2    |   Check if B swiped A → No |
T3    |   Save swipe               |
T4    |                            | Swipe right on A
T5    |                            |   Check if A swiped B → No
T6    |                            |   Save swipe
------|----------------------------|---------------------------
Result: Both swiped right, but NO MATCH created! ❌
```

**The Solution: Redis + Lua Script**

```java
public boolean recordSwipeAndCheckMatch(String swiperId, String targetUserId, Direction direction) {
    String swipeKey = buildSwipeKey(swiperId, targetUserId);
    String swiperField = swiperId + "_swipe";
    String targetField = targetUserId + "_swipe";

    // Lua script ensures atomicity
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptText(LUA_SCRIPT);
    script.setResultType(Long.class);

    Long result = redisTemplate.execute(
        script,
        Collections.singletonList(swipeKey),
        swiperField, targetField, direction.name(), String.valueOf(TTL)
    );

    return result != null && result == 1L;
}
```

**Lua Script** (Lines 18-33):
```lua
local key = KEYS[1]                      -- swipes:user1:user2
local from_field = ARGV[1]               -- user1_swipe
local to_field = ARGV[2]                 -- user2_swipe
local direction = ARGV[3]                -- RIGHT or LEFT
local ttl = tonumber(ARGV[4])            -- 24 hours

-- Step 1: Record this swipe
redis.call('HSET', key, from_field, direction)
redis.call('EXPIRE', key, ttl)

-- Step 2: Check inverse swipe
local inverse_swipe = redis.call('HGET', key, to_field)

-- Step 3: Return match result
if inverse_swipe == 'RIGHT' and direction == 'RIGHT' then
    return 1  -- Match!
else
    return 0  -- No match
end
```

**Why Lua**:
- Executes **atomically** in Redis
- No network round-trips between commands
- Guaranteed serialization (no race condition)

**Key Design**:
```
Redis Key: "swipes:123:456" (sorted user IDs)
Hash Fields:
  "123_swipe" → "RIGHT"
  "456_swipe" → "LEFT"
```

**Why sorted key?**: Ensures both users' swipes stored in same hash, regardless of who swiped first.

---

#### SwipeService.java - Orchestration

```java
@Transactional
public SwipeResponse processSwipe(String swiperId, String targetUserId, Direction direction) {
    // 1. Validation
    if (swiperId.equals(targetUserId)) {
        throw new IllegalArgumentException("Cannot swipe on yourself");
    }
    
    // Check users exist
    User swiper = userRepository.findById(swiperId).orElseThrow(...);
    User target = userRepository.findById(targetUserId).orElseThrow(...);
    
    // Prevent duplicate swipes
    if (swipeRepository.existsBySwiperIdAndTargetUserId(swiperId, targetUserId)) {
        throw new IllegalArgumentException("Already swiped on this user");
    }
    
    // 2. Persist swipe to database
    Swipe swipe = new Swipe();
    swipe.setSwiperId(swiperId);
    swipe.setTargetUserId(targetUserId);
    swipe.setDirection(direction);
    swipe = swipeRepository.save(swipe);
    
    // 3. Check for match (if right swipe)
    if (direction == Swipe.Direction.RIGHT) {
        boolean isMatch = matchDetectionService.recordSwipeAndCheckMatch(
            swiperId, targetUserId, direction
        );
        
        if (isMatch) {
            // 4. Create match record
            Match match = createMatch(swiperId, targetUserId);
            
            // 5. Send notifications (async)
            notificationService.sendMatchNotification(swiperId, targetUserId, match.getId());
            notificationService.sendMatchNotification(targetUserId, swiperId, match.getId());
            
            // 6. Cleanup Redis data (no longer needed)
            matchDetectionService.cleanupSwipeData(swiperId, targetUserId);
            
            return SwipeResponse.withMatch(swipe.getId(), match.getId());
        }
    }
    
    return SwipeResponse.noMatch(swipe.getId());
}
```

**Flow**:
1. Validate users and check duplicates
2. Save swipe to PostgreSQL (durable storage)
3. If RIGHT swipe: Check match in Redis (atomic)
4. If match: Create Match record
5. Send async notifications
6. Clean up Redis (match now in database)

**Why save to DB first?**: Even if Redis fails, swipe is recorded. Match can be detected via batch job.

---

#### FeedService.java - Match Discovery

```java
@Cacheable(value = "userFeed", key = "#userId + '_' + #limit", unless = "#result.isEmpty()")
public List<ProfileResponse> getPotentialMatches(String userId, Integer limit) {
    User currentUser = profileService.getUserEntity(userId);
    
    // Query database with geospatial filter
    List<User> potentialMatches = userRepository.findPotentialMatches(
        userId,
        currentUser.getLatitude(),
        currentUser.getLongitude(),
        currentUser.getMaxDistance(),
        currentUser.getAgeMin(),
        currentUser.getAgeMax(),
        currentUser.getInterestedIn().name(),
        limit
    );
    
    // Calculate distances and convert to DTOs
    return potentialMatches.stream()
        .map(user -> {
            double distance = calculateDistance(...);
            return ProfileResponse.fromUserWithDistance(user, distance);
        })
        .collect(Collectors.toList());
}
```

**Caching Strategy**:
- `@Cacheable`: Result cached in Redis for 15 minutes (see RedisConfig)
- Cache key: `userFeed:{userId}_{limit}`
- Cache eviction: On profile update (see ProfileService)

**Distance Calculation** (Haversine):
```java
private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
    final int EARTH_RADIUS = 6371; // km
    
    double latDistance = Math.toRadians(lat2 - lat1);
    double lonDistance = Math.toRadians(lon2 - lon1);
    
    double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
    
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    
    return EARTH_RADIUS * c;
}
```

---

### 5. Configuration

#### RedisConfig.java

```java
@Bean
public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setHashValueSerializer(new StringRedisSerializer());
    return template;
}

@Bean
public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(30));  // Default TTL

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(cacheConfig)
        .withCacheConfiguration("userProfile", cacheConfig.entryTtl(Duration.ofHours(1)))
        .withCacheConfiguration("userFeed", cacheConfig.entryTtl(Duration.ofMinutes(15)))
        .build();
}
```

**Why different TTLs**:
- **User profiles**: Change infrequently → 1 hour TTL
- **Feeds**: Location/preference sensitive → 15 min TTL

---

#### AsyncConfig.java

```java
@Bean(name = "taskExecutor")
public Executor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);    // Always-alive threads
    executor.setMaxPoolSize(20);     // Max threads
    executor.setQueueCapacity(500);  // Queue before rejection
    executor.setThreadNamePrefix("Async-");
    return executor;
}
```

**Used by**: `NotificationService.sendMatchNotification()` with `@Async` annotation.

---

### 6. Controllers (REST API)

#### SwipeController.java

```java
@PostMapping("/{swiperId}/{targetUserId}")
public ResponseEntity<SwipeResponse> swipe(
        @PathVariable String swiperId,
        @PathVariable String targetUserId,
        @Valid @RequestBody SwipeRequest request) {
    SwipeResponse response = swipeService.processSwipe(
        swiperId, targetUserId, request.getDecision()
    );
    return ResponseEntity.ok(response);
}
```

**Request**:
```json
POST /api/swipe/user123/user456
{
  "decision": "RIGHT"
}
```

**Response (Match)**:
```json
{
  "swipeId": "swipe789",
  "matched": true,
  "matchId": "match999",
  "message": "It's a match!"
}
```

**Response (No Match)**:
```json
{
  "swipeId": "swipe789",
  "matched": false,
  "matchId": null,
  "message": "Swipe recorded"
}
```

---

### 7. Exception Handling

#### GlobalExceptionHandler.java

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.NOT_FOUND.value(),
            ex.getMessage(),
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(...) {
        // Extract field errors and return
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(...) {
        // 400 Bad Request
    }
}
```

**Centralized error handling**: All controllers automatically use this handler.

---

## Data Flow Examples

### Example 1: Creating a Profile

```
1. POST /api/profile
   └─> ProfileController.createProfile()
       └─> ProfileService.createProfile()
           └─> UserRepository.save()
               └─> PostgreSQL INSERT

2. Response: ProfileResponse with userId
```

### Example 2: Getting Feed

```
1. GET /api/feed/{userId}?limit=50
   └─> FeedController.getFeed()
       └─> FeedService.getPotentialMatches()
           ├─> Check Redis cache (key: "userFeed:{userId}_50")
           │   └─> Cache HIT → Return cached feed
           │   └─> Cache MISS → Continue...
           ├─> ProfileService.getUserEntity() (get current user)
           └─> UserRepository.findPotentialMatches() (native query)
               ├─> Filter by age, gender, distance
               ├─> Exclude already-swiped users
               ├─> Calculate Haversine distance
               └─> ORDER BY RANDOM() LIMIT 50
           └─> Store result in Redis (TTL: 15 min)

2. Response: List<ProfileResponse> with distances
```

### Example 3: Swiping Right (Results in Match)

```
1. POST /api/swipe/{aliceId}/{bobId} { "decision": "RIGHT" }
   └─> SwipeController.swipe()
       └─> SwipeService.processSwipe()
           ├─> Validate users exist (UserRepository)
           ├─> Check duplicate swipe (SwipeRepository)
           ├─> Save swipe to PostgreSQL
           ├─> MatchDetectionService.recordSwipeAndCheckMatch()
           │   └─> Redis Lua script:
           │       ├─> HSET swipes:alice:bob alice_swipe RIGHT
           │       ├─> HGET swipes:alice:bob bob_swipe
           │       └─> Bob previously swiped RIGHT → MATCH!
           ├─> Create Match record (MatchRepository.save())
           ├─> NotificationService.sendMatchNotification() [ASYNC]
           │   └─> Send push to Alice
           ├─> NotificationService.sendMatchNotification() [ASYNC]
           │   └─> Send push to Bob
           └─> MatchDetectionService.cleanupSwipeData()
               └─> Redis DEL swipes:alice:bob

2. Response: SwipeResponse { matched: true, matchId: "..." }
```

---

## Key Implementation Patterns

### 1. DTO Pattern
- **Controllers** accept/return DTOs (ProfileRequest, SwipeResponse)
- **Services** work with domain models (User, Swipe, Match)
- **Separation**: API contract vs. internal representation

### 2. Repository Pattern
- **Repositories** abstract data access
- **Services** don't know about JPA/SQL details
- Enables easy testing with mock repositories

### 3. Service Layer Pattern
- **Controllers**: Thin, just handle HTTP
- **Services**: Business logic, orchestration
- **Repositories**: Data access only

### 4. Async Processing
- **NotificationService** uses `@Async`
- Doesn't block swipe response
- Fire-and-forget for non-critical operations

### 5. Caching Strategy
- **@Cacheable**: Declarative caching
- **@CacheEvict**: Invalidate on updates
- **Redis**: Backing cache store

---

## Performance Optimizations

### Database
1. **Indexes**:
   - `(latitude, longitude)` for geo queries
   - `(swiperId, targetUserId)` for duplicate checks
   - `(userId, createdAt)` for history queries

2. **Query Optimization**:
   - Native query avoids ORM overhead
   - `LIMIT` prevents unbounded results
   - `NOT IN` subquery filtered by index

### Caching
1. **Feed caching**: Expensive geo query cached 15 min
2. **Profile caching**: Reduces DB reads
3. **Redis for match detection**: In-memory speed

### Async
1. **Notifications**: Non-blocking, improves response time
2. **Thread pool**: Bounded, prevents resource exhaustion

---

## Testing Locally

### 1. Start Dependencies
```bash
docker run -d -p 5432:5432 -e POSTGRES_DB=tinder postgres:14-alpine
docker run -d -p 6379:6379 redis:7-alpine
```

### 2. Run Application
```bash
mvn spring-boot:run
```

### 3. Create Users
```bash
curl -X POST http://localhost:8080/api/profile \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Alice",
    "age": 28,
    "gender": "FEMALE",
    "interestedIn": "MALE",
    "ageMin": 25,
    "ageMax": 35,
    "maxDistance": 20,
    "latitude": 37.7749,
    "longitude": -122.4194
  }'
```

### 4. Get Feed
```bash
curl http://localhost:8080/api/feed/{userId}
```

### 5. Swipe
```bash
curl -X POST http://localhost:8080/api/swipe/{user1}/{user2} \
  -H "Content-Type: application/json" \
  -d '{"decision": "RIGHT"}'
```

### 6. Check Matches
```bash
curl http://localhost:8080/api/matches/{userId}
```

---

## Common Debugging Scenarios

### Issue: "Already swiped on this user"
- **Cause**: Duplicate swipe check in SwipeService line 43
- **Solution**: Check SwipeRepository for existing record
- **Query**: `SELECT * FROM swipes WHERE swiper_id = ? AND target_user_id = ?`

### Issue: Feed returns empty
- **Cause**: No users match preferences/distance
- **Debug**:
  1. Check user's preferences (ageMin, ageMax, maxDistance, interestedIn)
  2. Check other users' profiles
  3. Run query manually with relaxed filters
  4. Check swipe history (user may have swiped everyone)

### Issue: Match not created despite mutual swipes
- **Cause**: Redis issue or race condition
- **Debug**:
  1. Check Redis: `HGETALL swipes:user1:user2`
  2. Check PostgreSQL: `SELECT * FROM swipes WHERE ...`
  3. Check logs for MatchDetectionService
  4. Verify Lua script executed successfully

### Issue: Notifications not sent
- **Cause**: Async executor issue
- **Debug**:
  1. Check logs for "Sending match notification"
  2. Verify AsyncConfig thread pool not exhausted
  3. Check `@EnableAsync` on TinderApplication

---

## Next Steps for Enhancement

1. **Add tests**: Unit tests for services, integration tests for controllers
2. **Elasticsearch**: Replace native query with geo-indexed search
3. **Cassandra**: Migrate swipe storage for write scalability
4. **Real notifications**: Integrate APNS/FCM
5. **Rate limiting**: Prevent abuse (e.g., 100 swipes/day limit)
6. **Metrics**: Prometheus metrics for monitoring
7. **Circuit breakers**: Resilience4j for external service calls

---

## Questions?

For system design questions, see `STUDY_GUIDE.md`.
For API usage, see `README.md` or Swagger UI at http://localhost:8080/swagger-ui.html.
