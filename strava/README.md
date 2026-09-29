# Strava — Activity Tracking — System Design

> **Implementation status:** This repository contains a complete, runnable Spring Boot implementation of the Strava activity-tracking core, built around one central decision: **the client (phone) is the source of truth while recording.** The phone samples GPS, computes stats locally, buffers points offline, and syncs them to the server in idempotent, ordered batches. That single idea is what delivers offline support, accurate live stats, and scale to 10M concurrent activities.

**Stack:** Spring Boot 3.2 · H2 (in-memory) · Redis · Spring `@Async`

---

## Table of Contents

1. [Problem Statement & Requirements](#1-problem-statement--requirements)
2. [Capacity Estimation](#2-capacity-estimation)
3. [Core Entities & Data Model](#3-core-entities--data-model)
4. [API Design](#4-api-design)
5. [High-Level Architecture](#5-high-level-architecture)
6. [Deep Dive: Offline Tracking & Idempotent Batched Sync](#6-deep-dive-offline-tracking--idempotent-batched-sync)
7. [Deep Dive: Scaling to 10M Concurrent Activities](#7-deep-dive-scaling-to-10m-concurrent-activities)
8. [Deep Dive: Real-Time Friend Sharing (Beacon)](#8-deep-dive-real-time-friend-sharing-beacon)
9. [Deep Dive: Segment Leaderboards](#9-deep-dive-segment-leaderboards)
10. [Trade-offs & Alternatives](#10-trade-offs--alternatives)
11. [Running](#11-running)

---

## 1. Problem Statement & Requirements

Strava lets athletes record runs and rides on their phone, see live stats while they move, and share completed activities with friends. The interesting engineering problem is not the CRUD around activities — it is that recording happens **on a phone, often in a remote area with no signal, while the athlete needs accurate up-to-the-second stats the whole time**, and that the system must do this for millions of people at once.

### Functional Requirements

| # | Requirement |
|---|-------------|
| FR-1 | Users can **start, pause, resume, stop, and save** a run or ride |
| FR-2 | Users can view **live activity data** (route, distance, time, elevation, pace) *during* recording |
| FR-3 | Users can view their **own and their friends' completed** activities |
| FR-4 | **Segment leaderboards**: athletes compete over fixed stretches of road/trail, ranked by fastest time |
| FR-5 | **Real-time friend location sharing (Beacon)**: see where friends currently out on an activity are right now |

### Non-Functional Requirements

| Property | Target / Priority |
|----------|-------------------|
| Availability | **Availability >> consistency.** Recording must never be blocked by the network or the backend |
| Offline operation | Recording must work with **no network** (remote trails, tunnels, dead zones) and sync later |
| Live-stat accuracy | Local stats shown during the activity must be **accurate and up-to-date**, computed on-device |
| Scale | **10M concurrent in-progress activities**; ~100M+ registered users |
| Write path | Cheap enough to absorb 10M concurrent recorders; horizontally shardable |
| Read path | Feeds, activity detail, and leaderboards are read-heavy and **cache-friendly** |

### The central decision: the client is the source of truth while recording

Everything below follows from this. GPS is sampled on the phone. The phone computes distance/time/elevation locally and shows them instantly (no round-trip, works offline). Points are buffered on the device and synced to the server in **batches** tagged with a **client-assigned monotonic sequence**. The server merely *appends* the points and folds each one into running aggregate stats — a reconciliation of the client's own truth, not an authority over it.

This one decision buys three properties at once:

- **Offline support** — the phone keeps recording and computing stats with zero connectivity; batches flush when a signal returns.
- **Accurate live stats** — the UI reads on-device computation, never waiting on a server.
- **10M-concurrent scalability** — the write path is a tiny append plus a few arithmetic ops, sharded by activity/user, and clients sync at *their own cadence* (a small batch every few seconds) rather than streaming every GPS tick.

### Out of Scope

- Auth / sessions / OAuth
- Media (activity photos), kudos, comments, clubs, challenges
- Route/heatmap generation, GPS map-matching against a road network
- Push notifications, payments/subscriptions

---

## 2. Capacity Estimation

### Users & concurrency

```
Registered users:              ~100M+
Peak concurrent activities:    10M   (people recording a run/ride simultaneously)
```

### What one activity costs

```
A 1-hour activity sampled at 1 Hz  = ~3,600 GPS points
Raw point ≈ 40 bytes (lat, lng, elevation, timestamp, sequence)
Raw route ≈ 3,600 × 40 B ≈ 144 KB per activity

Encoded as a polyline (delta + varint), the stored route is far smaller —
typically single-digit KB — which is what makes long-term storage cheap.
```

### Write path — the reason batching matters

The naive design streams every GPS tick to the server:

```
STREAMING EVERY TICK:
  10M concurrent activities × 1 sample/sec = 10M writes/sec, forever,
  and it simply does not work offline.
```

The chosen design batches on the device and syncs periodically:

```
BATCHED SYNC (chosen):
  Each client flushes a small batch every ~10s.
  10M activities ÷ 10s ≈ 1M append-writes/sec

  Each write is: append N points + fold into running aggregates on ONE
  activity row. It touches only that activity's shard. No fan-out, no joins.
```

1M lightweight, shard-local append-writes/sec is an ordinary horizontally-sharded write workload. Contrast that with 10M/sec of un-batchable, network-coupled streaming that also fails the offline requirement. Batching is both a scale lever and a correctness lever.

### Read path (cache-friendly)

```
Completed feed:     friends' finished activities, newest first — indexed read on
                    (user_id, start_time), fanned out over a small friend set.
Live-friends feed:  short-TTL Redis reads (who's out right now).
Leaderboards:       Redis sorted sets — O(log N + K) top-K reads.
Activity detail:    single-row + route read; route is immutable once completed → trivially cacheable.
```

### Storage (order of magnitude)

```
Route points dominate. 100M activities/year × ~5 KB encoded route ≈ ~0.5 TB/year of route data,
plus small rows for activities, users, friendships, segments, and efforts.
Route points are append-only and immutable after completion → ideal for a partitioned/columnar cold store.
```

---

## 3. Core Entities & Data Model

The schema is deliberately lean and **reference-by-id** (no ORM back-references between aggregates), so the write path stays lightweight and every table is shardable by `user_id` or `activity_id`.

### User (`users`)
```java
@Entity @Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String name;      // NOT NULL
    private String email;
    private LocalDateTime createdAt;
}
```

### Activity (`activities`)

The heart of the system. It carries both the **finalized/running aggregate stats** and the **idempotency + running-state cursor** the ingestion path needs.

```java
@Entity
@Table(name = "activities", indexes = {
    @Index(name = "idx_activity_user",       columnList = "user_id"),
    @Index(name = "idx_activity_user_start", columnList = "user_id, start_time")
})
public class Activity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String userId;
    private ActivityType   type;    // RUN | RIDE
    private ActivityStatus status;  // ACTIVE | PAUSED | COMPLETED
    private String title;
    private LocalDateTime startTime, endTime;

    // --- Running aggregates (folded from each synced batch) ---
    private long   elapsedTimeSeconds;    // wall-clock across recorded samples
    private long   movingTimeSeconds;     // intervals above the moving-speed threshold
    private double distanceMeters;        // summed geodesic deltas
    private double elevationGainMeters;   // cumulative positive elevation
    private double averageSpeedMps;       // distance / movingTime, recomputed per batch
    private int    pointCount;

    // --- Idempotency guard + running-state cursor (previous sample) ---
    private long   lastSequence;          // highest applied batch sequence
    private Double lastLat, lastLng, lastElevationMeters;
    private LocalDateTime lastPointTime;

    private LocalDateTime createdAt, updatedAt;
}
```

Two field-groups deserve attention:

- **`lastSequence`** is the idempotency guard. Any incoming point with `sequence <= lastSequence` has already been applied and is skipped — retries are free.
- **`lastLat/lastLng/lastElevationMeters/lastPointTime`** are the *running-state cursor*: the previous sample, so the next point's distance/time/elevation delta can be computed incrementally without re-reading the whole route.

### RoutePoint (`route_points`)

One raw GPS sample. The unique constraint on `(activity_id, sequence)` enforces at-most-once storage per client sequence — the storage-layer half of idempotency.

```java
@Entity
@Table(name = "route_points",
    uniqueConstraints = @UniqueConstraint(name = "uq_route_activity_seq",
                                          columnNames = {"activity_id", "sequence"}),
    indexes = @Index(name = "idx_route_activity", columnList = "activity_id"))
public class RoutePoint {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String activityId;
    private long   sequence;      // client-assigned monotonic order
    private double latitude, longitude, elevationMeters;
    private LocalDateTime recordedAt;
}
```

### Friendship (`friendships`)

A directed edge; mutual friendships are stored as **two rows**, so resolving "who are my friends" is a single indexed read on `user_id`.

```java
@Entity
@Table(name = "friendships",
    uniqueConstraints = @UniqueConstraint(name = "uq_friend", columnNames = {"user_id", "friend_id"}),
    indexes = @Index(name = "idx_friend_user", columnList = "user_id"))
public class Friendship {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    private String userId;    // owner
    private String friendId;  // the friend
}
```

### Segment (`segments`) & SegmentEffort (`segment_efforts`)

A `Segment` is a fixed start→end stretch matched only against activities of the same `ActivityType`. A `SegmentEffort` is one athlete's timed traversal — the **durable source of truth** behind leaderboards (the Redis ZSET is a cache warmed from these rows).

```java
@Entity @Table(name = "segments")
public class Segment {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    private String name;
    private ActivityType activityType;
    private double startLat, startLng, endLat, endLng;
    private double distanceMeters;
    private double matchRadiusMeters = 30;   // proximity radius for start/end match
}

@Entity
@Table(name = "segment_efforts",
    indexes = @Index(name = "idx_effort_segment_time",
                     columnList = "segment_id, elapsed_time_seconds"))
public class SegmentEffort {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    private String segmentId, activityId, userId;
    private long   elapsedTimeSeconds;   // time between segment start and end samples
    private LocalDateTime achievedAt;
}
```

The composite index `(segment_id, elapsed_time_seconds)` makes "fastest efforts on this segment" an indexed range scan — the DB fallback when the cache is cold.

### Cache-only structures (Redis)

| Purpose | Key | Type | Value / Score | TTL |
|---------|-----|------|---------------|-----|
| Live location (Beacon) | `strava:live:{activityId}` | String (JSON) | `LiveLocationResponse` | `app.live.location-ttl-seconds` (default 300s) |
| Segment leaderboard | `strava:leaderboard:{segmentId}` | Sorted Set | member = `userId`, score = best `elapsedTimeSeconds` | none (durable-backed) |

---

## 4. API Design

All endpoints are under `/api`. These are the actual routes exposed by the controllers.

### 4.1 Users — `UserController`

```http
POST /api/users                      # create a user           → 201 UserResponse
GET  /api/users/{id}                 # fetch a user            → 200 UserResponse
```
```json
// POST /api/users
{ "name": "Alice Chen", "email": "alice@example.com" }
```

### 4.2 Activity lifecycle & GPS ingestion — `ActivityController`

```http
POST /api/activities                 # start (status=ACTIVE)   → 201 ActivityResponse
POST /api/activities/{id}/pause      # ACTIVE  → PAUSED         → 200 ActivityResponse
POST /api/activities/{id}/resume     # PAUSED  → ACTIVE         → 200 ActivityResponse
POST /api/activities/{id}/stop       # → COMPLETED (+ event)   → 200 ActivityResponse

POST /api/activities/{id}/points     # ingest a GPS batch      → 200 ActivityStatsResponse
GET  /api/activities/{id}            # activity + aggregates    → 200 ActivityResponse
GET  /api/activities/{id}/route      # ordered GPS points       → 200 [GpsPointDto]
GET  /api/activities/{id}/live       # live-location snapshot   → 200 LiveLocationResponse

GET  /api/users/{userId}/activities?page=0&size=20   # user's activities, newest first
```

**Start an activity:**
```json
// POST /api/activities
{ "userId": "u-123", "type": "RUN", "title": "Morning shakeout run" }
```

**Sync a batch of buffered GPS points** — the core write. Idempotent and sequence-ordered; re-submitting already-applied points is a no-op.
```json
// POST /api/activities/{id}/points
{
  "points": [
    { "sequence": 1, "latitude": 37.7719, "longitude": -122.4540, "elevationMeters": 12.0, "recordedAt": "2026-09-29T07:00:00" },
    { "sequence": 2, "latitude": 37.7719, "longitude": -122.4569, "elevationMeters": 14.0, "recordedAt": "2026-09-29T07:01:15" }
  ]
}
```
```json
// 200 ActivityStatsResponse — the client uses lastSequence to confirm what the server applied
{
  "activityId": "a-1", "status": "ACTIVE",
  "elapsedTimeSeconds": 75, "movingTimeSeconds": 75,
  "distanceMeters": 255.4, "elevationGainMeters": 2.0,
  "averageSpeedMps": 3.4, "pointCount": 2, "lastSequence": 2
}
```

### 4.3 Social feed — `FeedController`

```http
GET /api/users/{userId}/feed?page=0&size=20   # friends' COMPLETED activities → [ActivityResponse]
GET /api/users/{userId}/feed/live             # friends currently recording   → [LiveLocationResponse]
```

### 4.4 Segments & leaderboards — `LeaderboardController`

```http
GET /api/segments                                    # list all segments      → [Segment]
GET /api/segments/{segmentId}/leaderboard?limit=10   # ranked, fastest-first  → LeaderboardResponse
```
```json
// 200 LeaderboardResponse
{
  "segmentId": "s-1", "segmentName": "JFK Drive Sprint",
  "entries": [
    { "rank": 1, "userId": "u-bob", "userName": "Bob Martinez", "elapsedTimeSeconds": 300, "activityId": "a-9" }
  ]
}
```

> **Note on the social graph:** friendships are symmetric and seeded via `DataInitializer`; there is no follow/unfollow endpoint in this implementation (out of scope). `FriendshipService.getFriendIds()` reads the edges that the feed endpoints fan out from.

---

## 5. High-Level Architecture

```
                          ┌───────────────────────────────────────┐
                          │        Phone (source of truth)         │
                          │  • samples GPS at ~1 Hz                 │
                          │  • computes live stats ON DEVICE        │
                          │  • buffers points OFFLINE               │
                          │  • flushes ordered BATCHES on a signal  │
                          └───────────────────┬───────────────────┘
                                              │  POST /activities/{id}/points
                                              │  (batch, client-seq ordered, idempotent)
                                              ▼
                                       ┌──────────────┐
                                       │ Load Balancer │
                                       └──────┬───────┘
                                              ▼
                        ┌──────────────────────────────────────────┐
                        │            API / Service Tier             │
                        │  ActivityService   GpsIngestionService    │
                        │  FeedService        LiveActivityService   │
                        │  LeaderboardService FriendshipService     │
                        └───┬───────────────┬───────────────┬───────┘
                            │               │               │
              append+aggregate           read/write        publish
                            │               │           ActivityCompletedEvent
                            ▼               ▼               │ (AFTER_COMMIT, @Async)
                   ┌─────────────────┐   ┌──────────┐       ▼
                   │  Relational DB  │   │  Redis   │  ┌──────────────────┐
                   │  (H2 → Postgres/│   │          │  │ Segment matching │
                   │   sharded)      │   │ live:{id}│  │  (background)    │
                   │                 │   │  (TTL)   │  └────────┬─────────┘
                   │ activities      │   │          │           │ writes efforts
                   │ route_points    │   │ leader-  │◀──────────┘ + warms ZSET
                   │ segment_efforts │   │ board ZS │
                   │ users/friends   │   └──────────┘
                   └─────────────────┘        ▲
                            ▲                  │ getLiveFriendActivities / getLeaderboard
                            │                  │ (cache-first, DB fallback)
                            └──────────────────┘
```

Write path (batch ingest) is a small, shard-local append + arithmetic. Read paths (feeds, live, leaderboards) are cache-first with graceful degradation to the DB. Heavy post-processing (segment matching) is pushed off the request thread to `@Async` after the completing transaction commits.

---

## 6. Deep Dive: Offline Tracking & Idempotent Batched Sync

**This is the core of the design.** `GpsIngestionService.ingestBatch()` implements it.

### The flow

1. The phone records offline: it samples GPS, assigns each sample a **monotonic `sequence`**, computes live stats on-device, and buffers points in local storage.
2. Whenever it has connectivity, it POSTs a **batch** of buffered points to `/api/activities/{id}/points`.
3. The server sorts the batch by `sequence`, then for each point:
   - **Idempotency guard:** if `sequence <= activity.lastSequence`, skip it (already applied — a safe no-op for retried/duplicated batches).
   - **Append** the raw `RoutePoint` (protected further by the `(activity_id, sequence)` unique constraint).
   - **Fold into running aggregates** relative to the previous sample (the cursor):
     - distance `+=` haversine(cursor, point)
     - elapsed `+=` Δt; moving `+=` Δt only if `Δd/Δt >= movingSpeedThresholdMps` (default 0.5 m/s — filters out red lights and rest stops)
     - elevation gain `+=` positive elevation delta only
   - **Advance the cursor** (`lastLat/lastLng/lastElevationMeters/lastPointTime`) and set `lastSequence = point.sequence`.
4. Recompute `averageSpeedMps = distance / movingTime` and save the single activity row.
5. Best-effort: refresh the live-location Redis entry (never fails the write).

```java
for (GpsPointDto point : sortedBySequence) {
    if (point.getSequence() <= activity.getLastSequence()) continue;   // idempotent skip
    routePointRepository.save(toRoutePoint(point));                    // append
    if (activity.getLastLat() != null) {                               // fold delta
        double d = statsCalculator.haversineMeters(activity.getLastLat(), activity.getLastLng(),
                                                   point.getLatitude(), point.getLongitude());
        activity.setDistanceMeters(activity.getDistanceMeters() + d);
        // ... elapsed / moving / elevation deltas ...
    }
    advanceCursor(activity, point);
    activity.setLastSequence(point.getSequence());
}
```

### Why idempotency is non-negotiable here

Mobile networks are hostile: requests time out after the server committed, apps get killed mid-flush, users toggle airplane mode. The client must be free to **retry any batch** without fear. Two independent guards make that safe:

- **Application-level:** `sequence <= lastSequence` short-circuits already-applied points.
- **Storage-level:** the `(activity_id, sequence)` unique constraint rejects a duplicate raw point even if two batches race.

### Why the server *reconciles* rather than *owns* the stats

The phone already computed authoritative live stats on-device. The server re-derives the same aggregates from the synced points using the **identical haversine formula** (`StatsCalculator`) so the two agree. This is an honest trade-off: server-side stats are a **reconciliation** of the client's truth, not an independent authority. The client stays authoritative for the live UI; the server's copy exists for the feed, permanence, and leaderboards.

---

## 7. Deep Dive: Scaling to 10M Concurrent Activities

The requirement is 10M activities recording *at the same time*. The design meets it not with more hardware but by making each write cheap and independent.

### Why the write path is cheap

- **Client-as-source-of-truth** means the server never computes anything live or holds a session per recorder. There is no per-activity server-side state machine ticking in real time — just rows updated when a batch arrives.
- **Batching** turns "10M points/sec streamed" into "~1M small appends/sec" (a batch every ~10s), and every append is *offline-tolerant* — the client, not the server, absorbs connectivity gaps.
- **Each ingest touches exactly one activity row + N new route rows.** No fan-out, no cross-entity joins, no locks beyond that row.

### Why it shards linearly

`route_points` and `activities` partition cleanly by `activity_id` (or `user_id`). A batch for activity A only ever touches A's shard, so adding shards adds write throughput linearly with no coordination. `users`, `friendships`, `segment_efforts` shard by `user_id`/`segment_id` the same way.

```
Write cost per batch  = O(points in batch)          — a handful of arithmetic ops + appends
Cross-shard work      = none                          — batch → single activity shard
Server-side live state = none                          — client owns the live UI
Scaling knob          = shard count (activity_id)     — linear
```

### Contrast with the naive design

| | Stream every GPS tick | Batched client-as-truth (chosen) |
|---|---|---|
| Writes/sec @ 10M concurrent | ~10M/sec, un-batchable | ~1M/sec small appends |
| Offline recording | Impossible | Native |
| Live stats latency | Server round-trip | Instant (on-device) |
| Server per-recorder state | Yes (connection/stream) | None |
| Shardability | Hard (hot streams) | Trivial (by activity) |

### Keeping heavy work off the write path

Post-completion processing (segment matching) never runs inside the ingest or stop request. `ActivityService.stopActivity()` publishes an `ActivityCompletedEvent`; `LeaderboardService.onActivityCompleted()` consumes it with `@Async @TransactionalEventListener(AFTER_COMMIT)`, so the athlete's "stop" returns immediately and the background worker only ever reads committed data. The `AsyncConfig` pool uses `CallerRunsPolicy` so a saturated queue degrades gracefully instead of throwing.

---

## 8. Deep Dive: Real-Time Friend Sharing (Beacon)

Friends want to watch someone's run/ride *as it happens* (safety, encouragement). The design serves this from a **short-TTL Redis cache**, populated as a side effect of the same batch sync that already runs.

### Write side (free, best-effort)

At the end of every `ingestBatch`, the service writes the latest position + running aggregates to `strava:live:{activityId}` with a TTL (`LiveLocationCacheService.updateLocation`, default 300s). This write is wrapped in try/catch — **a Redis failure never breaks GPS ingestion.** The TTL means a live entry auto-expires shortly after the last batch, so "who is out right now" naturally self-cleans without a sweeper.

### Read side (cache-first, degrades gracefully)

- `GET /api/activities/{id}/live` (`LiveActivityService`) reads the Redis entry; on a miss (TTL expiry between batches, or Redis down) it falls back to the activity's **last persisted running-state** (`lastLat/lastLng/...`) so the endpoint never fails.
- `GET /api/users/{userId}/feed/live` (`FeedService.getLiveFriendActivities`) resolves the friend set, finds friends whose activities are `ACTIVE` (capped by a scan limit), and reads each one's live location — again with the persisted-state fallback.

```
ingestBatch ──► SET strava:live:{activityId} = {lat,lng,distance,elapsed}  (TTL 300s)
                     │
friend opens feed/live ─► for each ACTIVE friend activity:
                          GET strava:live:{id}  → hit  ► live position
                                                → miss ► activity.lastLat/lng snapshot
```

### Honest limitation

This is a **cache + polled read**, not a push. A production Beacon would push positions to spectators over **WebSocket** (or SSE) so the map moves without polling, and would likely use a geospatial structure for "friends near me." The cache-and-read model captured here is the correct backend primitive; the transport is the upgrade.

---

## 9. Deep Dive: Segment Leaderboards

A segment is a fixed start→end stretch of road/trail. Leaderboards rank athletes by their **best time** on a segment, fastest first.

### Populating efforts (async, after completion)

When an activity completes, `LeaderboardService.onActivityCompleted` runs on a background thread (`@Async`, `AFTER_COMMIT`) and:

1. Loads the activity's route points in sequence order.
2. For every `Segment` of the **same `ActivityType`**, does a naive geometric match: find the first route point within `matchRadiusMeters` of the segment **start**, then a later point within the radius of the segment **end**.
3. If both are found (and end is after start), records a `SegmentEffort` timed between those two samples and pushes the time into the Redis ZSET.

### Redis sorted sets for reads

`LeaderboardCacheService` stores `strava:leaderboard:{segmentId}` as a ZSET with `member = userId`, `score = elapsedTimeSeconds`. Two properties fall out for free:

- **Fastest-first** is a plain ascending range (`rangeWithScores(key, 0, limit-1)`), O(log N + K).
- **Best-per-athlete** is enforced on write: `addEffort` only overwrites a member's score when the new time is strictly faster.

### DB fallback + cache warming

If the ZSET is empty (cold cache) or Redis is down, `getLeaderboard` falls back to `segment_efforts` (indexed on `(segment_id, elapsed_time_seconds)`), pulls a wider slice, de-duplicates to each athlete's best via a `LinkedHashMap`, **warms the cache**, and returns the top `limit`. The DB is the durable source of truth; Redis is a rebuildable accelerator.

### Honest limitation

The start/end proximity match is intentionally naive — it can misfire on out-and-back routes or dense point clusters, and it re-scans candidate segments per activity. Production Strava uses **GPS map-matching** and a **spatial index** (e.g. geohash / R-tree / PostGIS) to pre-filter candidate segments by bounding box and to match the *shape* of the traversal, not just endpoint proximity. That is the clear upgrade path from this correct-but-simple starting point.

---

## 10. Trade-offs & Alternatives

### Source of truth while recording

| Approach | Pros | Cons | Verdict |
|----------|------|------|---------|
| **Client is source of truth (chosen)** | Offline recording; instant live stats; cheap, shardable write path | Server stats are a reconciliation; trusts client-computed points | ✅ The one decision the whole design rests on |
| Server is source of truth (stream every tick) | Single authority; server-validated stats | Fails offline; 10M writes/sec; server per-recorder state | ❌ Violates offline + scale requirements |

### Sync model

| Approach | Pros | Cons |
|----------|------|------|
| **Idempotent ordered batches (chosen)** | Retry-safe; offline-friendly; ~10× fewer writes | Live view lags by one batch interval |
| Per-tick streaming | Freshest server view | Un-batchable load; no offline; hot connections |

### Live friend sharing

| Approach | Pros | Cons |
|----------|------|------|
| **Redis cache + polled read (chosen here)** | Trivial; TTL self-cleans; degrades to DB | Not truly push; client polls |
| WebSocket/SSE push (production) | Real-time map, no polling | Connection management at scale; more infra |

### Leaderboard storage & matching

| Approach | Pros | Cons |
|----------|------|------|
| **Redis ZSET + DB efforts + naive proximity match (chosen)** | O(log N) top-K; rebuildable cache; simple | Endpoint-proximity match is approximate |
| GPS map-matching + spatial index (production) | Accurate matches; bounding-box candidate pruning | Significant algorithmic + infra complexity |

### Datastore

H2 in-memory here for a zero-setup demo. The schema (id-referenced aggregates, `user_id`/`activity_id` partition keys, append-only `route_points`) is designed to drop onto a **sharded relational store (PostgreSQL)** for hot/recent data with route points aging into partitioned/columnar cold storage. Redis is an optional accelerator, not a system of record — every cache path degrades to the DB.

---

## 11. Running

**Prerequisites:** JDK 21, Maven. Redis is **optional** — the app boots and runs without it; the live-location and leaderboard paths degrade gracefully to the database (and log a warning) when Redis is unreachable.

```bash
# From the strava/ project directory
mvn spring-boot:run

# (optional) start Redis to exercise the live-location + leaderboard cache paths
redis-server            # or: docker run -p 6379:6379 redis
```

The app starts on **http://localhost:8080** and seeds sample data on first boot (4 users, bidirectional friendships, 2 segments, 2 completed activities with routes and efforts) via `DataInitializer`.

| Resource | URL |
|----------|-----|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| H2 console | http://localhost:8080/h2-console — JDBC URL `jdbc:h2:mem:strava`, user `sa`, empty password |
| Actuator health | http://localhost:8080/actuator/health |

```bash
# Run the tests (they use the `test` profile, which disables sample-data seeding)
mvn test
```

### Try the core flow

```bash
# 1. Start a run for the seeded user (grab a userId from GET /api/users/{id} or the seed logs)
curl -s -X POST localhost:8080/api/activities \
  -H 'Content-Type: application/json' \
  -d '{"userId":"<USER_ID>","type":"RUN","title":"Lunch run"}'

# 2. Sync a batch of buffered GPS points (idempotent — safe to resend)
curl -s -X POST localhost:8080/api/activities/<ACTIVITY_ID>/points \
  -H 'Content-Type: application/json' \
  -d '{"points":[
        {"sequence":1,"latitude":37.7719,"longitude":-122.4540,"elevationMeters":12,"recordedAt":"2026-09-29T12:00:00"},
        {"sequence":2,"latitude":37.7719,"longitude":-122.4569,"elevationMeters":14,"recordedAt":"2026-09-29T12:01:15"}]}'

# 3. Watch it live, then stop and inspect
curl -s localhost:8080/api/activities/<ACTIVITY_ID>/live
curl -s -X POST localhost:8080/api/activities/<ACTIVITY_ID>/stop

# 4. Segment leaderboard (seeded) — list segments, then rank one
curl -s localhost:8080/api/segments
curl -s "localhost:8080/api/segments/<SEGMENT_ID>/leaderboard?limit=10"
```
