# Yelp Code Walkthrough

## Overview

This is a Spring Boot implementation of a Yelp-like local business search platform. It demonstrates the two core problems in local search: **proximity search** (finding businesses near a location) and **review aggregation** (maintaining accurate ratings without expensive on-the-fly computations).

## Suggested Reading Order

1. `model/Business.java` — data shape; notice the denormalized `starRating` / `reviewCount`
2. `model/Review.java` — review schema; notice no aggregate fields (they live on Business)
3. `search/GeohashUtil.java` — the geospatial math; standalone, no Spring dependencies
4. `service/BusinessSearchService.java` — the core search pipeline
5. `cache/SearchCacheService.java` — Redis caching with graceful degradation
6. `service/ReviewService.java` — write path + rating recalculation
7. `service/BusinessService.java` — business CRUD + geo index maintenance
8. `repository/BusinessRepository.java` — the bounding-box SQL queries
9. `controller/BusinessController.java` — REST endpoints
10. `config/DataInitializer.java` — sample data (SF businesses + reviews)

---

## Package Map

```
com.systemdesign.yelp/
├── YelpApplication.java          — Spring Boot entry point
├── model/
│   ├── Business.java             — JPA entity with denormalized star_rating, review_count
│   ├── Review.java               — JPA entity; UNIQUE(business_id, user_id)
│   ├── User.java                 — JPA entity
│   └── GeoCell.java              — Geohash index table; maps businessId → geohash cell
├── dto/
│   ├── BusinessSearchRequest.java — Search params: lat/lon, query, filters, sort, pagination
│   ├── BusinessSearchResponse.java — Paginated list of BusinessSummary
│   ├── BusinessDetailResponse.java — Full detail + star distribution histogram
│   ├── AddBusinessRequest.java   — New business payload
│   └── AddReviewRequest.java     — New review payload
├── repository/
│   ├── BusinessRepository.java   — findByBoundingBox, searchByTextAndBoundingBox, updateRating
│   ├── ReviewRepository.java     — findByBusinessId, averageStars, countStars (histogram)
│   ├── UserRepository.java       — findByEmail
│   └── GeoCellRepository.java    — findByGeohashPrefix, deleteByBusinessId
├── search/
│   └── GeohashUtil.java          — Geohash encode/decode, neighbor cells, Haversine
├── service/
│   ├── BusinessSearchService.java — Full search pipeline: cache → bbox → haversine → sort
│   ├── BusinessService.java      — Add/get/delete business + geo index maintenance
│   └── ReviewService.java        — Write review + recalculate rating + evict cache
├── cache/
│   └── SearchCacheService.java   — Redis wrapper: search results + business detail
├── controller/
│   ├── BusinessController.java   — /api/businesses endpoints
│   ├── ReviewController.java     — /api/businesses/{id}/reviews endpoints
│   └── GlobalExceptionHandler.java — 404/409/400 → structured error JSON
└── config/
    ├── RedisConfig.java          — StringRedisTemplate + ObjectMapper beans
    └── DataInitializer.java      — Loads SF sample data on startup
```

---

## Key Flow Traces

### Search Flow (GET /api/businesses/search)

```
BusinessController.search()
  └── builds BusinessSearchRequest from query params

BusinessSearchService.search(req)
  ├── 1. resolveRadius() — clamp to max 40km
  ├── 2. buildCacheKey() — round lat/lon to 3dp, include all filters
  ├── 3. searchCacheService.get(key) → return cached if hit
  ├── 4. Compute bounding box:
  │       latDelta = radius / 111
  │       lonDelta = radius / (111 × cos(lat))
  ├── 5. [if query provided]
  │       businessRepository.searchByTextAndBoundingBox(query, bbox)
  │       → LIKE query on name/category/description/tags
  │   [if no query]
  │       businessRepository.findByBoundingBox(bbox, category, minStars, maxPrice, openOnly)
  │       → SQL WHERE with B-tree index on lat/lon
  ├── 6. Stream candidates through:
  │       → haversine(userLat, userLon, biz.lat, biz.lon) <= radius
  │       → apply remaining filters (stars, price, openNow)
  ├── 7. Sort by: distance | rating | review_count
  ├── 8. Paginate: subList(page×size, (page+1)×size)
  ├── 9. Map to BusinessSummary DTOs (format price display, distance rounding)
  └── 10. searchCacheService.put(key, response)
```

### Write Review Flow (POST /api/businesses/{id}/reviews)

```
ReviewController.addReview()

ReviewService.addReview(req)
  ├── businessRepository.existsById() — 404 if business doesn't exist
  ├── reviewRepository.existsByBusinessIdAndUserId() — 409 if already reviewed
  ├── new Review → reviewRepository.save()
  ├── recalculateRating(businessId):
  │     └── reviewRepository.averageStarsByBusinessId() → avg
  │     └── reviewRepository.countByBusinessId() → count
  │     └── businessRepository.updateRating(id, avg, count)
  │           → UPDATE businesses SET star_rating=?, review_count=? WHERE id=?
  └── searchCacheService.evictBusinessDetail(businessId)
        → Redis DEL yelp:biz:{businessId}
```

### Add Business Flow (POST /api/businesses)

```
BusinessController.addBusiness()

BusinessService.addBusiness(req)
  ├── Map request → Business entity
  ├── businessRepository.save(business)
  └── indexGeoCell(business):
        → for precision in [4, 5, 6]:
            geohashUtil.encode(lat, lon, precision) → hash
            new GeoCell(businessId, hash, precision) → geoCellRepository.save()
```

---

## Why Key Decisions Were Made in Code

### Why `starRating` and `reviewCount` are columns on Business

`BusinessSearchService` sorts and filters by star rating at query time:
```java
.filter(sb -> req.getMinStars() == null || sb.business.getStarRating() >= req.getMinStars())
```
and:
```java
case "rating" -> Comparator.comparingDouble((ScoredBusiness sb) -> sb.business.getStarRating()).reversed();
```

If `starRating` were computed as `AVG(stars)` from the reviews table in the SQL query, it would require a JOIN + GROUP BY at 11,600 QPS — that's a full aggregation per search. Denormalization makes this a simple column read.

### Why bounding box comes before Haversine

The Haversine formula runs on every candidate business. At 500M businesses, running it without a pre-filter would be O(500M) per query. The bounding box SQL query uses a B-tree index on the `(latitude, longitude)` columns — this is an O(log N) → O(candidates) scan that reduces the candidate set from 500M to hundreds.

See `BusinessRepository.findByBoundingBox()` for the SQL, and the `latDelta`/`lonDelta` computation in `BusinessSearchService.search()`.

### Why cache keys round lat/lon to 3 decimal places

Line in `BusinessSearchService.buildCacheKey()`:
```java
double lat = Math.round(req.getLatitude() * 1000.0) / 1000.0;
double lon = Math.round(req.getLongitude() * 1000.0) / 1000.0;
```

3 decimal places = ~111m precision. Two users 50 meters apart share the same cache key. Without this rounding, every unique GPS coordinate (6+ decimal places from a phone) would generate its own cache entry — zero hit rate.

### Why business detail cache is evicted on review write, but search cache isn't

`ReviewService.addReview()` calls `searchCacheService.evictBusinessDetail(businessId)` after persisting a review. The rating just changed — showing a stale rating on the detail page would be confusing immediately after a user submits their review.

Search cache is not evicted because:
1. A business appears in hundreds of different search result sets (different queries, radii, pages)
2. Finding and deleting all search cache entries that contain this business would require a full Redis scan
3. 5-minute stale search results are acceptable — it's not financial data

### Why `recalculateRating` uses full AVG instead of incremental update

An incremental update would be:
```java
double newAvg = ((oldAvg * oldCount) + newStars) / (oldCount + 1);
```

The problem: floating-point operations accumulate error over thousands of reviews. After 10,000 reviews, the stored value may drift from the true average. The full recalculation:
```java
Double avg = reviewRepository.averageStarsByBusinessId(businessId);
```
is always accurate regardless of how many reviews exist. The SQL `AVG()` aggregation runs on an indexed column and is fast even for businesses with thousands of reviews.

### Why `GeoCell` stores multiple precision levels

`BusinessService.indexGeoCell()` creates three rows per business (precision 4, 5, 6):
```java
for (int precision : new int[]{4, 5, 6}) {
    String hash = geohashUtil.encode(lat, lon, precision);
    // ... save GeoCell
}
```

This allows proximity search at different radii without re-encoding:
- 1km search → precision 6 cells (tight)
- 5km search → precision 5 cells (default)
- 20km search → precision 4 cells (wide)

`GeohashUtil.precisionForRadius()` picks the appropriate level at query time.

### Why the test mock uses `@MockBean` for Redis

`BusinessSearchServiceTest` mocks `SearchCacheService` so tests run without Redis:
```java
@MockBean
private SearchCacheService searchCacheService;

when(searchCacheService.get(anyString())).thenReturn(Optional.empty());
```

This ensures tests always exercise the DB path (not the cache), and don't require a running Redis instance. The `SearchCacheService` tests would separately test the Redis integration.

---

## Dependency Flow

```
BusinessController
    └── BusinessSearchService
            ├── BusinessRepository (bounding-box SQL)
            ├── SearchCacheService (Redis read/write)
            └── GeohashUtil (distance math — no Spring deps)

ReviewController
    └── ReviewService
            ├── ReviewRepository (write + aggregate queries)
            ├── BusinessRepository (updateRating)
            └── SearchCacheService (evict on write)

BusinessService
    ├── BusinessRepository
    ├── ReviewRepository (star distribution histogram)
    ├── GeoCellRepository (geo index maintenance)
    ├── SearchCacheService (cache business detail)
    └── GeohashUtil (encode lat/lon → geohash)
```

---

## Key Invariants

1. `Review.businessId` always points to an existing `Business` — enforced in `ReviewService.addReview()`
2. `Review(businessId, userId)` is unique — enforced by service check + (implicit) application logic
3. `Business.starRating` always equals `AVG(stars)` from the reviews table — maintained by `ReviewService.recalculateRating()`
4. `GeoCell` entries always mirror the business's lat/lon — created on add, deleted on delete
5. Business detail cache is evicted whenever `starRating` changes — never shows stale rating on detail page
