# Yelp System Design — Interview Study Guide

## Problem Overview
**Question:** Design a local business search and review platform like Yelp.

**Difficulty:** Mid-Level (L4-L5)
**Duration:** 45 minutes

## Key Requirements (5 minutes)

### Functional
1. **Proximity Search** — Find businesses near user's location within a configurable radius
2. **Full-Text Search** — Search by name, category, description
3. **Filters** — Star rating, price range, open now, category
4. **Business Detail** — Full profile with photos, hours, star histogram
5. **Write Reviews** — One review per user per business; updates aggregate rating

### Non-Functional
1. **Scale**: 100M users, 500M businesses, 10M reviews
2. **Read QPS**: 11,600 searches/sec (1B/day)
3. **Latency**: p99 < 100ms search, < 200ms detail
4. **Availability**: 99.9% (few hours downtime/year)
5. **Consistency**: Eventual OK for search (5 min stale), immediate for business detail

---

## Capacity Estimation (5 minutes)

```
Users: 100M total, 10M DAU
Searches: 100 searches/user/day = 1B/day = 11,600 QPS

Storage:
  Businesses: 500M × 1KB = 500GB
  Reviews: 10M × 2KB = 20GB
  Images (S3): 500M businesses × 3 photos × 100KB = 150TB

Cache hit rate assumption: 80% (popular areas searched repeatedly)
Effective DB QPS: 11,600 × 0.20 = 2,300 QPS after cache

Write QPS:
  Reviews: ~100/sec
  Business adds: ~10/sec
```

---

## High-Level Architecture (10 minutes)

```
Client
  ↓
CDN (static assets, cached API responses for top cities)
  ↓
Load Balancer
  ↓
Search Service (stateless, scale horizontally)
  ├→ Redis Cache (search results: 5 min TTL, business detail: 10 min TTL)
  ├→ Elasticsearch (geo_distance + full-text search)
  └→ PostgreSQL Read Replica (business detail, reviews)

Write Service (business adds, review writes)
  └→ PostgreSQL Primary
       └→ Async: update Elasticsearch index
```

---

## Critical Design Decisions (20 minutes)

### 1. Geospatial Search Strategy ⭐⭐⭐ (The Core Problem)

**Options considered:**

| Approach | How It Works | Problem |
|----------|-------------|---------|
| ❌ Full Table Scan | `WHERE haversine(lat, lon) <= 5km` | O(500M) on every query |
| ⚠️ Bounding Box Only | SQL B-tree scan on lat/lon columns | Returns corners outside circle (superset) |
| ✅ Bounding Box + Haversine | Index scan → exact filter | Fast + correct |
| ✅✅ Elasticsearch geo_distance | Native geo index + full-text | Production choice |

**Chosen: Two-phase Bounding Box + Haversine**

Phase 1 — SQL bounding box (cheap B-tree scan, returns ~2× needed results):
```
latDelta = radius / 111 km/degree
lonDelta = radius / (111 × cos(lat))
WHERE lat BETWEEN (userLat - latDelta) AND (userLat + latDelta)
  AND lon BETWEEN (userLon - lonDelta) AND (userLon + lonDelta)
```

Phase 2 — Haversine post-filter (runs on small candidate set):
```java
double dist = 6371 × 2 × atan2(√a, √(1-a));
filter(dist <= radiusKm);
```

**Interview talking points:**
- "SQL B-tree on lat/lon columns eliminates 99%+ of rows cheaply"
- "Haversine only runs on hundreds of candidates, not 500M rows"
- "In production: Elasticsearch `geo_distance` query handles this natively"

---

### 2. Geohash for Partitioning ⭐⭐⭐

Geohash encodes (lat, lon) → base-32 string. Shared prefix = geographic proximity.

```
9q8yy → San Francisco Union Square ≈ 4.9km × 4.9km cell
9q8yz → cell immediately to the east
9q8y  → 4-char prefix covers ≈ 39km × 19km
```

**Neighbor lookup** (prevents boundary artifacts):
```
User is near the edge of cell 9q8yy
A business 200m away might be in cell 9q8yz
Solution: Always search center cell + 8 neighbors (3×3 grid)
```

**Precision selection:**
```
radius ≤ 1km → precision 6 (0.6km cells)
radius ≤ 5km → precision 5 (4.9km cells)
radius > 5km → precision 4 (39km cells)
```

**Interview talking points:**
- "Geohash is the spatial equivalent of a B-tree prefix scan"
- "Precision 5 gives ~4.9km cells — 9 cells covers a 15km² search area"
- "Elasticsearch uses geohash internally; same concept as PostGIS grid"

---

### 3. Denormalized Star Rating ⭐⭐

**Problem:** Search sorts by star rating at 11,600 QPS. Computing `AVG(stars)` per business on 10M reviews at every query is impossible.

**Solution: Precomputed aggregate columns on the business row**

```sql
-- Updated synchronously on every review write
UPDATE businesses
SET star_rating = 4.3,
    review_count = 127
WHERE id = ?
```

**Recalculation:** Full `SELECT AVG(stars), COUNT(*) FROM reviews WHERE businessId = ?`
- Accurate (no floating-point drift from incremental updates)
- Cheap (reviews indexed by businessId)
- Correct under concurrent writes (DB handles the aggregation)

**At >1K reviews/sec per business:**
- Write reviews to Kafka
- Async aggregation service: batch-updates ratings every 60 seconds
- Accept ~1 min stale rating

**Interview talking points:**
- "Denormalization is the standard trade-off for read-heavy aggregations"
- "Recalculate from source vs. incremental: choose recalculate for correctness"
- "Eventual consistency on rating is acceptable (5 stars vs. 4.9 stars doesn't matter)"

---

### 4. Redis Cache Design ⭐⭐

**Two cache layers:**

| Key | TTL | Eviction |
|-----|-----|----------|
| `yelp:search:{lat}:{lon}:{radius}:{filters}:{page}` | 5 min | Not targeted; rely on TTL |
| `yelp:biz:{businessId}` | 10 min | On new review (rating changed) |

**Coordinate rounding trick:**
```
Round lat/lon to 3 decimal places ≈ 111m precision
Users within 111m share the same cache key
Urban areas: 80%+ cache hit rate
```

**Why not evict search cache on review write?**
- A business appears in thousands of search result permutations
- Finding which keys contain it requires a secondary index
- 5-min stale results are acceptable (not financial data)
- Short TTL handles eventual consistency

**Interview talking points:**
- "Cache key rounding is the core insight — spatial locality boosts hit rate dramatically"
- "Selective invalidation for business detail; TTL-only for search"
- "Graceful degradation: Redis failure falls through to DB"

---

### 5. One Review Per User Per Business ⭐

Constraint enforced at multiple layers:
1. **Service layer**: `existsByBusinessIdAndUserId` check
2. **Database**: `UNIQUE(businessId, userId)` constraint

Why both?
- Service check gives a friendly error message
- DB constraint is the safety net for concurrent requests

**Interview talking points:**
- "Service layer for UX, DB constraint for data integrity"
- "Two concurrent requests can both pass the service check — DB unique constraint is the last line"

---

## Data Model (5 minutes)

```sql
businesses
  id UUID PK
  name, address, city, state
  latitude, longitude        -- B-tree index on (lat, lon)
  category                   -- B-tree index for filter
  star_rating DOUBLE         -- denormalized
  review_count INT           -- denormalized
  price_range INT            -- 1-4
  is_open BOOLEAN

reviews
  id UUID PK
  business_id UUID           -- index
  user_id UUID               -- UNIQUE with business_id
  stars INT
  text TEXT
  created_at TIMESTAMP       -- index for ordering

users
  id UUID PK
  email VARCHAR UNIQUE
  name
```

**Indexes:**
- `(lat, lon)` B-tree — enables bounding-box scan
- `(business_id, created_at)` on reviews — paginated review listing
- `(business_id, user_id)` UNIQUE — one-review-per-user enforcement

---

## Scaling (5 minutes)

**Read path:**
```
11,600 search QPS
× 80% cache hit = 9,280 QPS served by Redis
× 20% miss = 2,320 QPS → Elasticsearch
```

**Write path:**
```
100 reviews/sec → PostgreSQL → async reindex Elasticsearch
Rating recalc: 100/sec × ~1ms = negligible
```

**Elasticsearch cluster sizing:**
```
500M business docs × 1KB = 500GB data
With 2× replication = 1TB
500GB / 500GB per node = ~3 data nodes (with headroom)
```

**Database sharding by geography:**
```
US-West shard (lat 25-50, lon -130 to -95)
US-East shard (lat 25-50, lon -95 to -65)
EU shard (lat 35-72, lon -25 to 45)
```

Most searches are local → cross-shard queries are rare (<1%).

---

## Interview Talking Track

### Opening (2 min)
"I'll design a local business search platform handling 11,600 QPS reads and 100 review writes/sec. The core challenges are geospatial search at scale and keeping aggregate ratings current without slowing reads..."

### Requirements (5 min)
- Clarify: radius range? (default 5km, max 40km)
- Clarify: review edit allowed? (no — one immutable review)
- Out of scope: fraud detection, business owner responses, check-ins

### Capacity (5 min)
"1B searches/day = 11,600 QPS. With 80% cache hit rate, only 2,300 QPS hit the DB. Storage: 500M businesses × 1KB = 500GB..."

### High-Level (10 min)
"Search service checks Redis cache first. On miss, queries Elasticsearch with a geo_distance filter. Business detail served from Redis cache or PostgreSQL read replica..."

### Deep Dives (20 min)

**"How does proximity search work at 500M businesses?"**
- Phase 1: Bounding box SQL scan (lat/lon B-tree) — cheap, returns a superset
- Phase 2: Haversine post-filter on candidates — precise circle
- Production: replace with Elasticsearch geo_point index
- Geohash: prefix-based partitioning, 9-cell search handles boundaries

**"How do you keep star ratings current?"**
- Denormalize: store starRating + reviewCount on business row
- Recalculate from DB after each review write (accurate, not incremental)
- Evict business detail cache after rating change
- At scale: async via Kafka if write rate exceeds 1K/sec per business

**"What if the search area has millions of businesses?"**
- Add minimum star filter (most users want ≥ 3 stars)
- Add category filter (implicit from search query)
- Elasticsearch handles 500M docs efficiently with sharding
- Hard cap: return max 100 results per page

**"How do you handle the cache for search results?"**
- Round coordinates to 3 decimal places (~111m)
- Include all filters in the cache key
- Short TTL (5 min) for search, event-driven eviction for business detail
- Accept eventual consistency for search results

---

## Common Pitfalls

1. ❌ Full table scan with Haversine in WHERE clause
2. ❌ No bounding box pre-filter before Haversine
3. ❌ Computing AVG(stars) at query time instead of denormalizing
4. ❌ Forgetting the 8 neighbor cells for boundary handling
5. ❌ Cache key not including radius/filters → wrong results served
6. ❌ Immediately-consistent search cache invalidation (overly complex)
7. ❌ No UNIQUE constraint on (businessId, userId) in reviews table

---

## Follow-Up Questions

**"How do you handle photo uploads?"**
- Client uploads directly to S3 via presigned URL (bypass app servers)
- Business stores list of S3 keys
- CDN (CloudFront) serves photos at edge
- First photo = thumbnail shown in search results

**"How do you add business hours?"**
- Store as JSON: `{"mon": "9:00-21:00", "tue": "9:00-21:00", ...}`
- `isOpen` flag computed client-side or as a scheduled job
- "Open now" filter: app server computes at request time from hours + timezone

**"How do you prevent fake reviews?"**
- Machine learning classifier on review text
- Velocity limits: max 3 new reviews/day per user
- Require verified purchase or check-in
- "Elite" user badge for trusted reviewers

**"How would you add real-time recommendations?"**
- Collaborative filtering: users who liked A also liked B
- Pre-compute recommendation embeddings offline
- Serve from Redis at request time
- Cold start: use category/popularity as fallback

**"How does Elasticsearch sync stay consistent with PostgreSQL?"**
- Async: Debezium CDC reads PostgreSQL WAL → Kafka → Elasticsearch indexer
- Eventual consistency: Elasticsearch may lag by seconds
- Acceptable: Yelp doesn't need millisecond indexing of new businesses

---

## Key Numbers to Know

```
Searches: 11,600 QPS (1B/day)
Businesses: 500M (500GB at 1KB/business)
Reviews: 10M (20GB at 2KB/review)
Cache hit rate: 80% (popular urban areas)
Effective DB QPS: 2,300 after cache
Write QPS: ~100 reviews/sec

Geohash precision 5: 4.9km × 4.9km cells
9 cells cover ~221km² (15km radius equivalent)
1 degree latitude ≈ 111km
1 degree longitude ≈ 111 × cos(lat) km
```

---

## One-Page Cheat Sheet

```
YELP SYSTEM DESIGN — CHEAT SHEET

CORE CHALLENGE: Proximity search at 500M businesses, 11.6K QPS

SEARCH PIPELINE:
1. Cache check (Redis, 5 min TTL, rounded lat/lon key)
2. Bounding box SQL (B-tree on lat/lon)
3. Haversine post-filter (exact circle)
4. Sort + paginate
5. Cache result

GEOHASH:
- Encode (lat, lon) → base-32 prefix
- Prefix = cell, shared prefix = adjacent cells
- Search 9 cells (center + 8 neighbors) to handle boundaries
- Precision 5 = 4.9km cells for 5km default radius

STAR RATINGS:
- Denormalize: store starRating + reviewCount on business row
- Recalculate from DB after each review write
- Evict biz detail cache; let search cache expire (5 min)

WRITE PATH:
- Validate: one review per user per business (DB UNIQUE constraint)
- Persist review → recalc rating → evict business detail cache

CACHE KEYS:
- Search: yelp:search:{lat_3dp}:{lon_3dp}:{radius}:{filters}
- Biz: yelp:biz:{businessId}  ← evicted on new review

SCALE:
- Elasticsearch for geo_distance + full-text (replaces SQL bounding box)
- PostgreSQL sharded by geography
- Redis cluster for cache
```
