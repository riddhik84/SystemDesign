# Tinder System Design

A production-quality implementation of a Tinder-like dating application system design using Spring Boot, Redis, and PostgreSQL.

## Overview

This implementation demonstrates a scalable matching platform with:
- Profile management with user preferences
- Geospatial-based match discovery
- Atomic swipe processing with Redis for consistency
- Real-time match detection
- Push notification simulation

## Architecture

### Core Components

1. **Profile Service** - User profile and preference management
2. **Feed Service** - Potential match discovery with geospatial filtering
3. **Swipe Service** - Swipe processing and match detection orchestration
4. **Match Detection Service** - Atomic match validation using Redis + Lua scripts
5. **Match Service** - Match retrieval and management
6. **Notification Service** - Async push notification simulation

### Technology Stack

- **Framework**: Spring Boot 3.2.3
- **Language**: Java 21
- **Database**: PostgreSQL (relational data, user profiles, swipes, matches)
- **Cache**: Redis (atomic match detection, feed caching, profile caching)
- **Build Tool**: Maven
- **API Documentation**: SpringDoc OpenAPI / Swagger UI

## Key Design Decisions

### 1. Consistent Match Detection

Uses **Redis with Lua scripting** to solve the race condition problem where two users swipe right simultaneously:

```lua
-- Atomic operations in single Redis command
HSET swipes:user1:user2 user1_swipe RIGHT
CHECK inverse swipe (user2_swipe)
RETURN match=true if both RIGHT
```

**Why Lua?**: Guarantees atomicity without distributed locking complexity. Alternative would be single-partition Cassandra transactions.

### 2. Geospatial Query Optimization

PostgreSQL native query with Haversine formula:
- Filters by preferences (age, gender, distance)
- Excludes previously swiped users
- Returns randomized results to avoid predictable ordering

**Production alternative**: Elasticsearch/OpenSearch with geo-spatial indexing for sub-100ms queries at scale.

### 3. Feed Pre-computation & Caching

- Feed results cached for 15 minutes (configurable TTL)
- Profile data cached for 1 hour
- Cache invalidation on profile updates and location changes
- Prevents stale matches while maintaining low latency

### 4. Data Model

**Users Table**:
- Profile data with preferences (age range, gender, distance)
- Geospatial coordinates for proximity matching
- Indexed on location for efficient queries

**Swipes Table**:
- Partition by swiper_id for fast lookups
- Unique constraint prevents duplicate swipes
- Indexed on (swiper, target) and timestamp

**Matches Table**:
- Normalized user pairs (user1_id < user2_id)
- Indexed for bidirectional lookups
- Soft delete with `active` flag

## Scale Considerations

### Current Implementation (Single Instance)

- Handles ~10K DAU
- PostgreSQL for all persistence
- Single Redis instance for atomic operations

### Production Scaling Path

**Database Tier**:
- Shard PostgreSQL by user_id for write distribution
- Read replicas for feed queries
- Consider Cassandra for swipe history (write-optimized, 200GB/day at 20M DAU)

**Cache Tier**:
- Redis Cluster with consistent hashing
- Separate cache pools: match detection (low latency) vs. feed cache (larger TTL)

**Application Tier**:
- Horizontal scaling with load balancer
- Stateless design enables easy replication

**Target Scale**:
- 20M daily active users
- 2B swipes/day (100 swipes/user avg)
- <300ms feed generation latency
- Strong consistency for match detection

## API Endpoints

### Profile Management
- `POST /api/profile` - Create profile
- `PUT /api/profile/{userId}` - Update profile
- `GET /api/profile/{userId}` - Get profile

### Feed & Discovery
- `GET /api/feed/{userId}?limit=50` - Get potential matches

### Swiping
- `POST /api/swipe/{swiperId}/{targetUserId}` - Process swipe
  ```json
  { "decision": "RIGHT" | "LEFT" }
  ```

### Matches
- `GET /api/matches/{userId}` - Get user's matches

## Running the Application

### Prerequisites

- Java 21+
- Maven 3.8+
- PostgreSQL 14+
- Redis 7+

### Setup

1. **Start PostgreSQL**:
```bash
docker run -d -p 5432:5432 \
  -e POSTGRES_DB=tinder \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  postgres:14-alpine
```

2. **Start Redis**:
```bash
docker run -d -p 6379:6379 redis:7-alpine
```

3. **Build & Run**:
```bash
cd tinder
mvn clean install
mvn spring-boot:run
```

### Access Points

- Application: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- API Docs: http://localhost:8080/api-docs
- Health: http://localhost:8080/actuator/health

## Example Usage

### 1. Create User Profiles

```bash
# User 1 (Alice)
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
    "longitude": -122.4194,
    "bio": "Love hiking and coffee"
  }'

# User 2 (Bob)
curl -X POST http://localhost:8080/api/profile \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bob",
    "age": 30,
    "gender": "MALE",
    "interestedIn": "FEMALE",
    "ageMin": 24,
    "ageMax": 32,
    "maxDistance": 25,
    "latitude": 37.7849,
    "longitude": -122.4094,
    "bio": "Software engineer, enjoy cooking"
  }'
```

### 2. Get Feed

```bash
curl http://localhost:8080/api/feed/{userId}?limit=20
```

### 3. Swipe Right

```bash
curl -X POST http://localhost:8080/api/swipe/{aliceId}/{bobId} \
  -H "Content-Type: application/json" \
  -d '{"decision": "RIGHT"}'
```

### 4. Get Matches

```bash
curl http://localhost:8080/api/matches/{userId}
```

## Testing

Run unit and integration tests:
```bash
mvn test
```

## Monitoring

Actuator endpoints available:
- `/actuator/health` - Application health
- `/actuator/metrics` - Application metrics
- `/actuator/prometheus` - Prometheus-formatted metrics

## Production Considerations

### What's Implemented
✅ Atomic match detection with Redis + Lua  
✅ Geospatial filtering with Haversine distance  
✅ Duplicate swipe prevention  
✅ Feed caching with configurable TTL  
✅ Async notification processing  
✅ Comprehensive error handling  
✅ API documentation with Swagger  

### Production Enhancements Needed
- [ ] Elasticsearch integration for feed queries at scale
- [ ] Cassandra for swipe history storage
- [ ] Kafka for async event processing
- [ ] Bloom filters for client-side duplicate detection
- [ ] Rate limiting (100 swipes/user/day)
- [ ] Image upload & CDN integration
- [ ] Real-time chat system
- [ ] Machine learning recommendation engine
- [ ] Multi-region deployment with geo-routing
- [ ] APNS/FCM integration for real push notifications
- [ ] Database sharding strategy
- [ ] Circuit breakers and retry logic
- [ ] Comprehensive monitoring/alerting

## References

- Original Design: https://www.hellointerview.com/learn/system-design/problem-breakdowns/tinder
- Geospatial Indexing: Elasticsearch Geo Queries
- Consistency: Redis Lua Scripting
- Scale: Horizontal Partitioning Strategies
