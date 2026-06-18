# Tinder System Design - Study Guide

## Interview Preparation Guide

This guide helps you understand and articulate the Tinder system design for technical interviews.

## 1. Problem Statement

**Goal**: Design a dating application like Tinder that allows users to:
- Create profiles with preferences (age, distance, gender)
- View potential matches one at a time
- Express interest via left/right swipes
- Get notified when mutual interest occurs (match)

**Scale**: 20M daily active users, ~100 swipes/user/day = **2B swipes daily**

---

## 2. Core Requirements

### Functional Requirements
1. **Profile Management**: Users set preferences (age range, max distance, gender interest)
2. **Match Discovery**: Show stack of potential matches filtered by preferences + proximity
3. **Swiping**: Users swipe right (like) or left (pass) on profiles
4. **Match Notifications**: Mutual right swipes trigger instant match notification

### Non-Functional Requirements
1. **Strong Consistency**: Mutual swipes MUST result in match (no lost matches)
2. **Low Latency**: Feed generation < 300ms
3. **Scale**: Handle 2B swipes/day
4. **Deduplication**: Never show already-swiped profiles

---

## 3. Key Technical Challenges

### Challenge 1: Race Condition in Match Detection

**Problem**: Two users swipe right simultaneously
- User A swipes right on User B at time T
- User B swipes right on User A at time T
- Without proper synchronization, both writes complete but neither sees the other's swipe
- Result: No match created despite mutual interest ❌

**Solution Options**:

**Option A: Redis with Atomic Operations (Implemented)**
```
Key: "swipes:123:456" (sorted user IDs)
Value: {
  "123_swipe": "right",
  "456_swipe": "left"
}

Lua Script:
1. HSET key userA_swipe "right"
2. HGET key userB_swipe
3. IF both RIGHT → return MATCH
```

**Why Lua?**: Executes atomically in Redis, guarantees serialized access.

**Option B: Cassandra Single-Partition Transactions**
```sql
CREATE TABLE swipes (
  user_pair text,      -- partition key: "user1:user2" (sorted)
  from_user uuid,
  to_user uuid,
  direction text,
  PRIMARY KEY ((user_pair), from_user, to_user)
);
```
Both swipes land in same partition → lightweight transaction ensures atomicity.

**Interview Tip**: Explain the race condition clearly with timing diagram. Discuss tradeoffs:
- Redis: Lower latency, requires cache durability strategy
- Cassandra: Durable by default, slightly higher latency

---

### Challenge 2: Low-Latency Feed Generation

**Problem**: For each feed request, must:
1. Filter users by preferences (age, gender, distance)
2. Calculate geospatial distance (Haversine formula)
3. Exclude already-swiped users
4. All in < 300ms

**Naive Approach** (Too Slow):
```sql
SELECT * FROM users
WHERE age BETWEEN 25 AND 35
  AND gender = 'FEMALE'
  AND calculate_distance(lat, lng) < 20km
  AND id NOT IN (SELECT target_id FROM swipes WHERE swiper_id = ?)
ORDER BY RANDOM()
LIMIT 50;
```
→ Full table scan with expensive distance calculation = **1-3 seconds** ❌

**Solution: Hybrid Pre-computation + Indexed Queries**

**Implementation**:
1. **Elasticsearch/OpenSearch** with geo-spatial index
   - Index users by location (geo_point field)
   - Query: `geo_distance` filter + age/gender filters
   - Returns candidates in < 100ms

2. **Pre-computed Feed Cache** (Implemented)
   - Background job computes feeds for active users
   - Cached in Redis with 15-30 min TTL
   - Feed refresh triggers:
     - User depletes current stack
     - Location change > 5km
     - Preference update

3. **Client-Side Deduplication**
   - Client caches last 1000 swiped user IDs
   - Filters out duplicates before rendering
   - For extensive history: Bloom filter (zero false negatives)

**Interview Tip**: Discuss tradeoffs of pre-computation:
- **Pro**: Sub-100ms latency, consistent UX
- **Con**: Stale profiles (mitigated with short TTL)
- **When to refresh**: Location changes, preference updates, stack depleted

---

### Challenge 3: Deduplication at Scale

**Problem**: With 2B swipes/day, swipe history grows unbounded. How to prevent showing already-swiped profiles?

**Solutions by Scale**:

1. **Database Check** (Current Implementation)
   ```sql
   WHERE id NOT IN (SELECT target_id FROM swipes WHERE swiper_id = ?)
   ```
   - Works for < 1M users
   - Inefficient for large swipe histories

2. **Client-Side Cache**
   - Store last K swiped user IDs locally (e.g., K = 1000)
   - Filter on client before rendering
   - Misses older swipes but acceptable UX

3. **Bloom Filter** (Production Scale)
   - Probabilistic data structure: 10 bits/item
   - 100K swipes = 125KB memory
   - Zero false negatives (never show swiped profile)
   - Small false positive rate (occasionally skip valid profile)

**Interview Tip**: Explain bloom filter properties:
- **Hash-based**: k hash functions map item to k bits
- **False negatives**: Impossible (if bit is 0, item definitely not seen)
- **False positives**: Possible (bit collision from other items)
- **Tradeoff**: Memory vs. accuracy

---

## 4. System Architecture

### High-Level Components

```
┌──────────┐
│  Mobile  │
│  Client  │
└────┬─────┘
     │
┌────▼───────────┐
│  API Gateway   │  (Load Balancer, Rate Limiting)
└────┬───────────┘
     │
     ├──────────────────┬───────────────┬─────────────────┐
     │                  │               │                 │
┌────▼────────┐  ┌─────▼──────┐  ┌────▼──────┐  ┌──────▼────────┐
│  Profile    │  │   Feed     │  │  Swipe    │  │ Notification  │
│  Service    │  │  Service   │  │  Service  │  │   Service     │
└─────┬───────┘  └─────┬──────┘  └────┬──────┘  └───────────────┘
      │                │               │
      │          ┌─────▼──────┐        │
      │          │Elasticsearch│       │
      │          │  (Geo Index)│       │
      │          └────────────┘        │
      │                                │
┌─────▼─────────────────┬──────────────▼───────┐
│   PostgreSQL          │      Redis           │
│  (User, Swipe, Match) │ (Match Detection)    │
└───────────────────────┴──────────────────────┘
```

### Data Flow: Swipe Processing

1. **Client** → POST /swipe/{userId} `{"decision": "RIGHT"}`
2. **Swipe Service**:
   - Validate users exist
   - Check duplicate swipe
   - Save to PostgreSQL
3. **Match Detection Service**:
   - Atomic Redis operation (Lua script)
   - Check inverse swipe
   - Return match=true/false
4. **If Match**:
   - Create Match record
   - Send push notification to both users
   - Cleanup Redis swipe data
5. **Response**: `{"matched": true, "matchId": "..."}`

---

## 5. Data Model

### Users Table
```sql
CREATE TABLE users (
  id UUID PRIMARY KEY,
  name VARCHAR(100),
  age INT,
  gender VARCHAR(20),
  interested_in VARCHAR(20),
  age_min INT,
  age_max INT,
  max_distance INT,
  latitude DECIMAL(10,8),
  longitude DECIMAL(11,8),
  bio TEXT,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  active BOOLEAN
);

CREATE INDEX idx_location ON users(latitude, longitude);
```

### Swipes Table
```sql
CREATE TABLE swipes (
  id UUID PRIMARY KEY,
  swiper_id UUID,
  target_user_id UUID,
  direction VARCHAR(10),  -- 'RIGHT' or 'LEFT'
  created_at TIMESTAMP,
  UNIQUE(swiper_id, target_user_id)
);

CREATE INDEX idx_swiper_target ON swipes(swiper_id, target_user_id);
CREATE INDEX idx_swiper_time ON swipes(swiper_id, created_at);
```

### Matches Table
```sql
CREATE TABLE matches (
  id UUID PRIMARY KEY,
  user1_id UUID,  -- Always smaller UUID
  user2_id UUID,  -- Always larger UUID
  created_at TIMESTAMP,
  active BOOLEAN
);

CREATE INDEX idx_user1_user2 ON matches(user1_id, user2_id);
CREATE INDEX idx_user1_time ON matches(user1_id, created_at);
CREATE INDEX idx_user2_time ON matches(user2_id, created_at);
```

**Why normalize user pairs?** Ensures single match row regardless of who swiped first.

---

## 6. API Design

### 1. Create/Update Profile
```
POST /api/profile
{
  "age_min": 20,
  "age_max": 30,
  "distance": 10,
  "interestedIn": "female" | "male" | "both"
}
```

### 2. Get Feed
```
GET /api/feed/{userId}?limit=50
→ User[]
```

### 3. Swipe
```
POST /api/swipe/{swiperId}/{targetUserId}
{
  "decision": "RIGHT" | "LEFT"
}
→ {
  "matched": boolean,
  "matchId": string | null
}
```

### 4. Get Matches
```
GET /api/matches/{userId}
→ Match[]
```

---

## 7. Capacity Planning

### Scale Calculations

**Assumptions**:
- 20M daily active users (DAU)
- 100 swipes/user/day
- 50% right swipes
- 1% match rate

**Daily Load**:
- **Swipes**: 20M × 100 = 2B swipes/day
- **Writes/sec**: 2B / 86400 = ~23K writes/sec
- **Matches**: 2B × 1% = 20M matches/day

**Storage**:
- **Swipe record**: 100 bytes (UUID + UUID + direction + timestamp)
- **Daily growth**: 2B × 100 bytes = 200 GB/day
- **Annual growth**: 200 GB × 365 = ~73 TB/year

**Read Load**:
- **Feed requests**: 20M users × 5 sessions/day = 100M feed requests/day
- **Reads/sec**: 100M / 86400 = ~1.2K reads/sec
- **With cache (90% hit rate)**: ~120 reads/sec to DB

**Redis Memory**:
- **Active swipe pairs**: Assume 10M concurrent pairs
- **Memory/pair**: 200 bytes (key + 2 fields)
- **Total**: 10M × 200 bytes = 2 GB

---

## 8. Scaling Strategy

### Database Tier

**PostgreSQL Scaling**:
1. **Read Replicas**: Feed queries go to replicas
2. **Partitioning**: Shard by `user_id` hash
   - Profile writes distributed
   - Swipe writes distributed
   - Match queries may need fan-out

**Why Cassandra for Swipes?**
- Write-optimized (2B writes/day)
- Horizontal scaling out-of-box
- Partition by `swiper_id` → all user's swipes co-located
- Time-series data (older swipes can be archived)

### Cache Tier

**Redis Cluster**:
- Consistent hashing for key distribution
- Separate pools:
  - **Match detection**: Low latency, small dataset
  - **Feed cache**: Larger dataset, relaxed latency
- Replication for high availability

### Application Tier

**Stateless Services**:
- Horizontal scaling with load balancer
- Each service independently scalable:
  - Feed Service: CPU-intensive (geo calculations)
  - Swipe Service: Write-heavy
  - Notification Service: I/O-bound

---

## 9. Interview Discussion Points

### When Asked About Tradeoffs

**Consistency vs. Latency**:
- Q: "Why not eventual consistency for matches?"
- A: User expectation is instant notification. Lost match = bad UX. Strong consistency is non-negotiable here.

**Pre-computation vs. Real-Time**:
- Q: "Why pre-compute feeds? Why not query on-demand?"
- A: Geospatial queries are expensive (1-3s). Pre-computation trades freshness for latency. Mitigate with short TTL (15 min).

**SQL vs. NoSQL**:
- Q: "Why not use MongoDB/DynamoDB for everything?"
- A: 
  - PostgreSQL: Great for users (relational, ACID, geo queries with PostGIS)
  - Cassandra: Better for swipes (write-optimized, time-series)
  - Redis: Essential for atomic operations (match detection)
  - Elasticsearch: Optimized for geo + text search

### How to Scale Further

**1. Geographic Distribution**:
- Deploy in multiple regions (US-West, US-East, Europe, Asia)
- Route users to nearest datacenter
- Profile data can be globally replicated
- Swipe/Match data can be regional

**2. Feed Generation Optimization**:
- Machine learning recommendation engine
- Consider user preferences beyond explicit filters:
  - Swipe patterns (who you usually like)
  - Active times
  - Engagement signals

**3. Advanced Features**:
- Photo upload → CDN + image processing
- Real-time chat → WebSocket infrastructure
- Video profiles → streaming infrastructure
- Super likes, rewinds → premium feature tracking

---

## 10. Common Interview Questions

### Q1: "How do you handle two users swiping right at the exact same time?"

**Answer**:
1. Explain the race condition with timing diagram
2. Describe Redis + Lua script solution (atomic operations)
3. Discuss alternative: Cassandra lightweight transactions
4. Explain tradeoff: Latency vs. durability

### Q2: "How do you ensure low latency for feed generation?"

**Answer**:
1. Explain expensive geospatial query problem
2. Describe pre-computation + caching strategy
3. Mention Elasticsearch for real-time queries
4. Discuss refresh triggers and TTL tradeoffs

### Q3: "How do you prevent showing duplicate profiles?"

**Answer**:
1. Database exclusion query (simple but slow)
2. Client-side cache for recent swipes
3. Bloom filter for extensive history
4. Explain bloom filter properties (no false negatives)

### Q4: "What's your database schema and why?"

**Answer**:
1. Walk through Users, Swipes, Matches tables
2. Explain indexes (location, swiper-target composite)
3. Discuss partitioning strategy (by user_id)
4. Mention Cassandra for swipe storage at scale

### Q5: "How do you scale to 100M users?"

**Answer**:
1. **Database**: Shard by user_id, Cassandra for swipes
2. **Cache**: Redis cluster with consistent hashing
3. **Application**: Horizontal scaling, load balancing
4. **Search**: Elasticsearch cluster with replicas
5. **Geographic**: Multi-region deployment

---

## 11. Key Takeaways

✅ **Race Condition**: Use Redis + Lua or Cassandra LWT for atomic match detection  
✅ **Latency**: Pre-compute feeds, cache aggressively, use Elasticsearch for geo queries  
✅ **Deduplication**: Bloom filters for efficient history checks at scale  
✅ **Schema**: Normalize match pairs, index on location and swiper-target  
✅ **Scaling**: Shard by user_id, separate read/write pools, multi-region deployment  

---

## 12. Practice Questions

1. How would you implement "undo" (rewind last swipe)?
2. How would you rank potential matches (not just filter)?
3. How would you handle users changing location frequently?
4. How would you implement "boost" (show profile to more users)?
5. How would you detect and prevent fake profiles/bots?
6. How would you implement chat after matching?
7. How would you handle user reporting and safety features?

---

## Resources

- [Geospatial Indexing with Elasticsearch](https://www.elastic.co/guide/en/elasticsearch/reference/current/geo-queries.html)
- [Redis Lua Scripting](https://redis.io/docs/manual/programmability/eval-intro/)
- [Bloom Filters Explained](https://en.wikipedia.org/wiki/Bloom_filter)
- [Cassandra Data Modeling](https://cassandra.apache.org/doc/latest/data_modeling/)
