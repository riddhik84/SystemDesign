# Yelp Local Business Search — System Design

A scalable implementation of a Yelp-like local business search platform with geospatial proximity search, full-text filtering, and review aggregation. Built with Spring Boot to handle 100M users, 1B searches/day, and 10M reviews.

## Overview

This project demonstrates the architecture and implementation of a local business discovery platform where users can:
- Search for businesses by proximity (within a configurable radius)
- Filter by category, star rating, price range, and open status
- Read and write reviews with star ratings
- View full business detail pages with star distribution histograms
- Browse paginated search results sorted by distance, rating, or review count

## System Requirements

### Functional Requirements
- **Proximity Search**: Find businesses within a radius (default 5km, max 40km)
- **Full-Text Search**: Match query against name, category, description, and tags
- **Business Detail**: View full profile with reviews and star histogram
- **Write Reviews**: One review per user per business; recalculates aggregate rating
- **Review Reactions**: Vote reviews as useful/funny/cool

### Non-Functional Requirements
- **Scale**: 100M users, 500M businesses, 10M reviews
- **Read throughput**: 1B searches/day (~11.6K QPS)
- **Latency**: p99 < 100ms for search, p99 < 200ms for detail
- **Write throughput**: ~100 reviews/second
- **Availability**: 99.9% uptime

## Architecture

### High-Level Components

```
┌──────────────────────────────────────────────────────────┐
│                      Clients (Web/Mobile)                │
└─────────────────────────┬────────────────────────────────┘
                          │ HTTPS
                          v
┌──────────────────────────────────────────────────────────┐
│               API Gateway / Load Balancer                │
└────┬────────────────────┬────────────────────────────────┘
     │                    │
     v                    v
┌──────────────┐   ┌──────────────────────────────────────┐
│ Write Service│   │         Search Service               │
│              │   │                                      │
│ - AddBusiness│   │  1. Cache check (Redis)              │
│ - AddReview  │   │  2. Bounding-box DB query            │
│ - UpdateBiz  │   │  3. Haversine circle filter          │
│              │   │  4. Text/star/price filters          │
│              │   │  5. Sort + paginate                  │
│              │   │  6. Cache result                     │
└──────┬───────┘   └──────────────┬───────────────────────┘
       │                          │
       v                          v
┌──────────────────────────────────────────────────────────┐
│              PostgreSQL (Primary DB)                     │
│  - businesses (geospatial columns: lat, lon)            │
│  - reviews (businessId FK, userId FK)                   │
│  - users                                                │
│  - geo_index (geohash lookup table)                     │
└──────────────────────────────────────────────────────────┘
       │                          │
       v                          v
┌──────────────┐   ┌──────────────────────────────────────┐
│  Elasticsearch│   │           Redis Cache                │
│  (production) │   │  yelp:search:{key}  TTL: 5 min      │
│  - geo_point  │   │  yelp:biz:{id}      TTL: 10 min     │
│  - full-text  │   └──────────────────────────────────────┘
│  - faceting   │
└──────────────┘
```

### Key Design Decisions

#### 1. Geospatial Search: Bounding Box + Haversine

**The problem:** Finding all businesses within 5km of a point across 500M rows.

**Naive approach:** `SELECT * FROM businesses WHERE haversine(lat, lon, ?, ?) <= 5km`
- Scans all 500M rows — O(N), unusable at scale

**Our approach (two-phase):**
1. **Bounding-box pre-filter** (cheap SQL index scan):
   ```sql
   WHERE lat BETWEEN minLat AND maxLat
     AND lon BETWEEN minLon AND maxLon
   ```
   Converts the circle to a circumscribed rectangle — fast B-tree scan, returns a superset.

2. **Haversine post-filter** (precise circle):
   ```java
   double dist = haversine(userLat, userLon, bizLat, bizLon);
   return dist <= radiusKm;
   ```
   Runs on the small candidate set from step 1.

**Production upgrade:** Replace with Elasticsearch `geo_distance` query — same two-phase logic but with an inverted geo index. No full-table scans at any scale.

**Why not PostGIS?**
- PostGIS is excellent but requires PostgreSQL extension and spatial indexes
- Elasticsearch is already needed for full-text search — consolidates the tech stack
- Same bounding-box → Haversine pattern applies at the application layer either way

#### 2. Geohash for Spatial Indexing

Geohash encodes (lat, lon) → a base-32 string where shared prefixes = geographic proximity:

```
precision 4 → cell ≈ 39km × 19km
precision 5 → cell ≈  4.9km ×  4.9km  ← default for 5km radius
precision 6 → cell ≈  1.2km ×  0.6km
```

Search strategy:
1. Encode user location to precision 5 geohash (e.g. `9q8yy`)
2. Find 8 neighboring cells to handle boundary artifacts
3. Query businesses in those 9 cells
4. Post-filter to exact Haversine distance

This reduces candidates from 500M → hundreds before Haversine runs.

```
  [9q8yw][9q8yx][9q8yy]
  [9q8wu][9q8wv][9q8wt]   ← 9 cells to search
  [9q8ws][9q8wt][9q8wu]
```

#### 3. Denormalized Star Rating (Read Optimization)

Businesses store a precomputed `starRating` and `reviewCount` column:

```sql
UPDATE businesses SET star_rating = 4.3, review_count = 127 WHERE id = ?
```

**Why?**
- Search results must sort/filter by rating
- `AVG(stars) GROUP BY businessId` on 10M reviews × 11.6K QPS = impossible
- Denormalization trades write complexity for read performance
- Recalculate on every review write (accurate, cheap for this write rate)

**At >1K reviews/sec:** shift to async aggregation via Kafka — review events stream to an aggregation service that batch-updates ratings every 60 seconds.

#### 4. Redis Cache for Search Results

Cache key includes rounded coordinates + filters:
```
yelp:search:37.775:-122.419:5.0:pizza:restaurant::2:distance:0:20
```

Coordinates rounded to 3 decimal places (≈111m precision) — users within 111m share the same cache entry, dramatically increasing hit rate in dense areas.

**Cache invalidation:**
- **Search results**: No targeted invalidation. 5-min TTL for eventual consistency.
  Rationale: a new business or review appearing 5 min late is acceptable.
- **Business detail**: Evicted immediately on new review (rating changed).
  Rationale: users expect the rating to update immediately after writing.

#### 5. One Review Per User Per Business

```java
if (reviewRepository.existsByBusinessIdAndUserId(businessId, userId)) {
    throw new IllegalStateException("User already reviewed this business");
}
```

Enforced at the service layer + UNIQUE constraint on (businessId, userId) in the DB.
Prevents fake-review spam and duplicate counting in rating aggregation.

## Data Model

```
Business
  id: UUID (PK)
  name, address, city, state, zipCode, country
  latitude, longitude                    -- indexed with B-tree, used in bounding-box
  category                               -- indexed for exact-match filter
  description, phone, website, ownerId
  starRating: DOUBLE                     -- denormalized, updated on each review
  reviewCount: INT                       -- denormalized
  priceRange: INT (1-4)
  isOpen: BOOLEAN
  createdAt, updatedAt

Review
  id: UUID (PK)
  businessId: UUID (FK → businesses)    -- indexed
  userId: UUID (FK → users)             -- UNIQUE with businessId
  stars: INT (1-5)
  text: TEXT
  usefulCount, funnyCount, coolCount: INT
  createdAt, updatedAt

User
  id: UUID (PK)
  email: VARCHAR (UNIQUE)
  name
  reviewCount
  eliteYear

GeoCell (geohash index)
  id: UUID (PK)
  businessId: UUID
  geohashCell: VARCHAR(12)              -- indexed
  precision: INT (4, 5, or 6)
  latitude, longitude
```

## API Design

```
GET  /api/businesses/search
     ?latitude=37.7749&longitude=-122.4194
     &query=pizza
     &category=Italian
     &radiusKm=5
     &minStars=4
     &maxPriceRange=3
     &openNow=true
     &sortBy=rating      # distance | rating | review_count
     &page=0&pageSize=20

GET  /api/businesses/{businessId}
     → full detail + star distribution

POST /api/businesses
     { name, address, lat, lon, category, description, ... }

DELETE /api/businesses/{businessId}

GET  /api/businesses/{businessId}/reviews?page=0&pageSize=20
POST /api/businesses/{businessId}/reviews
     { userId, stars, text, imageUrls }

GET  /api/users/{userId}/reviews?page=0&pageSize=20

POST /api/reviews/{reviewId}/useful?voterId=user1
```

## Getting Started

### Prerequisites
- Java 17+
- Maven 3.8+
- Redis (optional — app degrades gracefully without it)

### Running Locally

1. **Start Redis (optional)**
```bash
docker run -d -p 6379:6379 redis:alpine
```

2. **Build and Run**
```bash
cd yelp
mvn clean install
mvn spring-boot:run
```

3. **Sample data is pre-loaded**: 5 SF businesses, 3 users, 10 reviews

### Quick Test

```bash
# Search for pizza near Union Square SF
curl "http://localhost:8080/api/businesses/search?latitude=37.7879&longitude=-122.4074&query=pizza&radiusKm=5"

# Search for coffee sorted by rating
curl "http://localhost:8080/api/businesses/search?latitude=37.7749&longitude=-122.4194&category=Coffee&sortBy=rating"

# Get all businesses within 10km
curl "http://localhost:8080/api/businesses/search?latitude=37.7749&longitude=-122.4194&radiusKm=10"

# Get business detail
curl http://localhost:8080/api/businesses/{id}

# Add a review
curl -X POST http://localhost:8080/api/businesses/{id}/reviews \
  -H "Content-Type: application/json" \
  -d '{"userId":"newuser","stars":5,"text":"Amazing place!"}'
```

## Scaling Strategy

### Horizontal Read Scaling
```
Write QPS: ~100 reviews/sec + ~10 business writes/sec
Read QPS:  ~11,600 searches/sec + ~5,800 detail requests/sec

Tier 1 — Redis cache (handles ~80% of reads)
Tier 2 — Search service fleet (stateless, scale horizontally)
Tier 3 — Elasticsearch cluster (handles uncached searches)
Tier 4 — PostgreSQL read replicas (for detail + review pages)
```

### Database Sharding
- **By geographic region**: US-East, US-West, EU, APAC
  - Most searches are local → cross-shard queries rare
  - Clear operational boundary: add EU shard when EU traffic grows
- **Alternatively by businessId hash**: even distribution but cross-region queries

### Elasticsearch for Production Search
Replace `BusinessRepository.findByBoundingBox` with:
```json
{
  "query": {
    "bool": {
      "filter": [
        {"geo_distance": {"distance": "5km", "location": {"lat": 37.7749, "lon": -122.4194}}},
        {"term": {"category": "pizza"}}
      ],
      "should": [
        {"multi_match": {"query": "pizza", "fields": ["name^3", "category^2", "description"]}}
      ]
    }
  },
  "sort": [{"_score": "desc"}, {"_geo_distance": {"location": {"lat": 37.7749, "lon": -122.4194}, "order": "asc"}}]
}
```

## Technology Stack

- **Framework**: Spring Boot 3.2.0
- **Language**: Java 17
- **Database**: H2 (dev) / PostgreSQL (prod) / Elasticsearch (search)
- **ORM**: Spring Data JPA + Hibernate
- **Cache**: Redis 7
- **Build Tool**: Maven

## Testing

```bash
mvn test
```

Tests cover:
- Proximity radius filtering (businesses outside radius excluded)
- Full-text query matching (name, category)
- Sort ordering (distance, rating)
- Pagination
- Review write + rating recalculation
- Duplicate review prevention
- Geohash encoding/decoding/neighbors
- Haversine distance formula

## Learning Objectives

1. **Geospatial indexing**: Bounding-box → Haversine two-phase search
2. **Geohash**: Prefix-based proximity, neighbor cells for boundary handling
3. **Denormalization**: Precomputed ratings for read-heavy workloads
4. **Cache design**: Key rounding for hit rate, selective invalidation
5. **Write path**: Atomic rating recalculation after review persisted

## References
- [System Design Interview — Yelp](https://www.hellointerview.com/learn/system-design/problem-breakdowns/yelp)
- [Elasticsearch Geo Distance Query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-geo-distance-query.html)
- [Geohash Wikipedia](https://en.wikipedia.org/wiki/Geohash)

## License

Educational system design project. Not for production use.
