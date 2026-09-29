# Strava Code Walkthrough

## Overview

This is a Spring Boot implementation of Strava-style activity tracking. It demonstrates the hardest problem in fitness-tracking systems: recording accurate runs and rides that must work **offline in remote areas**, stay **accurate and up-to-date locally during the activity**, and still **scale to ~10M concurrent activities** — all while keeping availability well ahead of consistency.

**The one central decision everything else follows from:** while recording, the **client (phone) is the source of truth**. The phone samples GPS, computes distance/time/elevation locally for the live on-device UI, buffers points offline, and syncs them to the server in **idempotent, ordered BATCHES** stamped with a client-assigned **monotonic sequence**. The server appends the points and folds each one into **running aggregates** on the activity row. This single choice buys three things at once:

1. **Offline support** — the phone keeps recording with no network and syncs buffered batches whenever connectivity returns.
2. **Accurate live stats** — the authoritative numbers live on the device; the server reconciles the same math from the synced batches.
3. **10M-concurrent scalability** — the write path is a tiny append plus a handful of arithmetic ops, shardable by user/activity. Clients sync a small batch every ~10s (roughly 1M lightweight append-writes/sec across the fleet), rather than streaming every GPS tick.

Two real-time/social features layer on top: **friend feeds** (completed activities + live "who is out right now" beacons served from a short-TTL Redis cache) and **segment leaderboards** (Redis sorted sets keyed by best time, populated by async segment-matching after an activity completes). Every Redis call degrades gracefully so the app survives a cache outage.

Stack: **Spring Boot 3.2 · H2 (in-memory) · Redis · Spring @Async**.

> Honest trade-offs baked into this implementation: (1) server-side stats are **reconciled** from client batches — the client stays authoritative for the live UI; (2) leaderboard segment-matching is a **naive geometric start/end proximity match** (production would use GPS-map-matching against a spatial index); (3) friend live-sharing uses a **Redis cache + feed poll** (production would push over WebSocket).

## Suggested Reading Order

1. `model/Activity.java` — the heart of the write path: running aggregates + `lastSequence` idempotency guard + `last*` running-state cursor
2. `model/RoutePoint.java` — one raw GPS sample; UNIQUE(activity_id, sequence) is the storage-level idempotency backstop
3. `dto/GpsPointDto.java` + `dto/GpsBatchRequest.java` — the client-assigned `sequence` and the batch envelope that makes offline sync work
4. `service/StatsCalculator.java` — the haversine helper; the same formula the phone uses, re-derived server-side
5. `service/GpsIngestionService.java` — THE CORE: idempotent batched append + running-aggregate fold + best-effort live-location write
6. `service/ActivityService.java` — the ACTIVE→PAUSED→ACTIVE→COMPLETED state machine; publishes `ActivityCompletedEvent` on stop
7. `event/ActivityCompletedEvent.java` — the event that decouples "stop activity" from leaderboard work
8. `cache/LiveLocationCacheService.java` — Redis string `strava:live:{activityId}`, short TTL, graceful degradation
9. `service/LiveActivityService.java` — live read: cache first, else persisted running-state fallback
10. `service/FriendshipService.java` — symmetric friend graph (both directions stored)
11. `service/FeedService.java` — friend feed (completed) + live-friends view, both read-time fanout
12. `cache/LeaderboardCacheService.java` — Redis ZSET `strava:leaderboard:{segmentId}`, best-time-per-user
13. `service/LeaderboardService.java` — async segment matching on completion + leaderboard read with DB fallback
14. `service/UserService.java` — user CRUD + `resolveName` used by feed/leaderboard responses
15. `controller/*` — the REST surface (activity, feed, leaderboard, user)
16. `config/AsyncConfig.java` — shared async pool (CallerRunsPolicy) that runs the AFTER_COMMIT leaderboard work
17. `config/RedisConfig.java` — StringRedisTemplate + JSR-310 ObjectMapper
18. `config/DataInitializer.java` — demo seed (`@Profile("!test")`)

---

## Package Map

```
com.systemdesign.strava/
├── StravaApplication.java              — Spring Boot entry point (@EnableCaching, @EnableAsync)
├── model/
│   ├── Activity.java                   — JPA entity; running aggregates (distance/elapsed/moving/elevation/avgSpeed/pointCount) + lastSequence guard + last{Lat,Lng,ElevationMeters,PointTime} cursor
│   ├── RoutePoint.java                 — JPA entity; one GPS sample; UNIQUE(activity_id, sequence); index on activity_id
│   ├── Segment.java                    — JPA entity; named road/trail with start/end coords, activityType, matchRadiusMeters
│   ├── SegmentEffort.java              — JPA entity; one athlete's timed traversal; index (segment_id, elapsed_time_seconds)
│   ├── Friendship.java                 — JPA entity; directed edge (user_id → friend_id); UNIQUE(user_id, friend_id); index user_id
│   ├── User.java                       — JPA entity; lean athlete identity (id, name, email)
│   ├── ActivityStatus.java             — enum ACTIVE / PAUSED / COMPLETED
│   └── ActivityType.java               — enum RUN / RIDE (scopes segment matching)
├── event/
│   └── ActivityCompletedEvent.java     — ApplicationEvent (activityId, userId); triggers async leaderboard matching
├── dto/
│   ├── GpsPointDto.java                — sequence, lat, lng, elevationMeters, recordedAt
│   ├── GpsBatchRequest.java            — @NotEmpty List<GpsPointDto> points (the sync envelope)
│   ├── StartActivityRequest.java       — userId, type, optional title
│   ├── ActivityResponse.java           — full activity view incl. aggregates + resolved userName
│   ├── ActivityStatsResponse.java      — lightweight post-ingest snapshot incl. lastSequence (client reconciliation)
│   ├── LiveLocationResponse.java       — activityId, userId, lat/lng, distance, elapsed, updatedAt (beacon payload)
│   ├── LeaderboardEntry.java           — rank, userId, userName, elapsedTimeSeconds, activityId
│   ├── LeaderboardResponse.java        — segmentId, segmentName, List<LeaderboardEntry>
│   ├── CreateUserRequest.java          — @NotBlank name, email
│   └── UserResponse.java               — id, name, email
├── repository/
│   ├── ActivityRepository.java         — findByUserIdOrderByStartTimeDesc; findByUserIdInAndStatusOrderByStartTimeDesc (feed + live)
│   ├── RoutePointRepository.java       — findByActivityIdOrderBySequenceAsc (route render + segment match)
│   ├── SegmentRepository.java          — findByActivityType (same-type matching)
│   ├── SegmentEffortRepository.java    — findBySegmentIdOrderByElapsedTimeSecondsAsc (leaderboard DB fallback)
│   ├── FriendshipRepository.java       — findByUserId (friend id set)
│   └── UserRepository.java             — plain JpaRepository<User, String>
├── service/
│   ├── GpsIngestionService.java        — idempotent batch append + running-aggregate fold + live-location write
│   ├── ActivityService.java            — lifecycle state machine (start/pause/resume/stop) + reads (get/list/route) + toResponse
│   ├── StatsCalculator.java            — @Component; haversineMeters great-circle distance
│   ├── LiveActivityService.java        — live-location read: cache-first, persisted-state fallback
│   ├── FeedService.java                — friend completed-activity feed + live-friends view (read-time fanout)
│   ├── FriendshipService.java          — getFriendIds (symmetric graph)
│   ├── LeaderboardService.java         — @Async AFTER_COMMIT segment matching + leaderboard read (cache → DB fallback → warm)
│   └── UserService.java                — create/get user; resolveName; toResponse
├── cache/
│   ├── LiveLocationCacheService.java   — Redis string: strava:live:{activityId} = LiveLocationResponse JSON, TTL 300s
│   └── LeaderboardCacheService.java    — Redis ZSET: strava:leaderboard:{segmentId}, member=userId, score=best elapsed
├── controller/
│   ├── ActivityController.java         — /api/activities lifecycle + /points ingest + /route + /live; /users/{id}/activities
│   ├── FeedController.java             — GET /api/users/{id}/feed and /feed/live
│   ├── LeaderboardController.java      — GET /api/segments and /segments/{id}/leaderboard
│   ├── UserController.java             — POST /api/users, GET /api/users/{id}
│   └── GlobalExceptionHandler.java     — NoSuchElement→404, IllegalState/IllegalArgument→400, validation→400, else→500
└── config/
    ├── RedisConfig.java                — StringRedisTemplate + JSR-310 ObjectMapper beans
    ├── AsyncConfig.java                — stravaAsyncExecutor (core=4, max=16, queue=500, CallerRunsPolicy) + async exception handler
    └── DataInitializer.java            — seeds 4 users, bidirectional friendships, 2 segments, 2 completed activities w/ routes + efforts (@Profile("!test"))
```

---

## Key Flow Traces

### (a) WRITE PATH: GPS Batch Ingest / Offline Sync

This is the path the whole design is built around. The phone buffers points offline and POSTs them in batches; each batch is appended idempotently and folded into the running aggregates.

```
POST /api/activities/{id}/points   body = { points: [ {sequence, lat, lng, elevationMeters, recordedAt}, ... ] }

ActivityController.ingestPoints(id, batch)      // @Valid → 400 if points empty
  └── GpsIngestionService.ingestBatch(id, batch)   // @Transactional

GpsIngestionService.ingestBatch(activityId, batch)
  ├── activityRepository.findById(activityId)          → 404 NoSuchElementException if missing
  ├── if status == COMPLETED → 400 IllegalStateException  (no points after finalization)
  ├── SORT batch.points by sequence ASC   (out-of-order / merged offline buffers apply correctly)
  └── FOR each point:
        ├── IDEMPOTENCY GUARD: if point.sequence <= activity.lastSequence → skip (already applied; safe retry)
        ├── save RoutePoint(activityId, sequence, lat, lng, elevationMeters, recordedAt)
        ├── FOLD into running aggregates relative to the last* cursor (only if a previous point exists):
        │     ├── d = StatsCalculator.haversineMeters(lastLat,lastLng → lat,lng);  distanceMeters += d
        │     ├── dtSeconds = recordedAt − lastPointTime
        │     │     if dt > 0: elapsedTimeSeconds += dt
        │     │                speed = d/dt;  if speed >= movingSpeedThresholdMps(0.5): movingTimeSeconds += dt
        │     └── elevationDelta = elevationMeters − lastElevationMeters;  if > 0: elevationGainMeters += delta
        ├── ADVANCE cursor: lastLat/lastLng/lastElevationMeters/lastPointTime = this point; pointCount++; lastSequence = sequence
        └── latestApplied = point
  ├── averageSpeedMps = movingTimeSeconds > 0 ? distanceMeters/movingTimeSeconds : 0
  ├── activityRepository.save(activity)        (persist new aggregates + advanced cursor)
  ├── if latestApplied != null:                BEST-EFFORT live-location write (never fails the tx):
  │     liveLocationCacheService.updateLocation(activityId, LiveLocationResponse{lat,lng,distance,elapsed,updatedAt=now})
  │       → Redis SET strava:live:{activityId} = JSON, TTL 300s   (try/catch no-op on failure)
  └── return ActivityStatsResponse{...aggregates..., lastSequence}   ← client uses lastSequence to confirm what stuck
```

Key point: because the guard compares `sequence <= lastSequence`, re-POSTing a batch the server already saw (flaky network, app restart) advances nothing and double-counts nothing. `RoutePoint`'s `UNIQUE(activity_id, sequence)` is the storage-level backstop for the same invariant.

### (b) ACTIVITY LIFECYCLE: start → stop

A small state machine on `Activity.status`. Illegal transitions raise `IllegalStateException` → 400.

```
POST /api/activities                 ActivityService.startActivity(req)   // @Transactional
  ├── userService.getUserEntity(userId)     → 404 if user missing
  ├── new Activity{status=ACTIVE, startTime=now, aggregates all 0, lastSequence=0, last*=null}
  ├── activityRepository.save(...)          → @GeneratedValue UUID id, createdAt via @PrePersist
  └── 201 CREATED  ActivityResponse

   ... phone streams GPS batches via path (a); status stays ACTIVE ...

POST /api/activities/{id}/pause      pauseActivity   → requires ACTIVE, else 400;  status = PAUSED
POST /api/activities/{id}/resume     resumeActivity  → requires PAUSED, else 400;  status = ACTIVE

POST /api/activities/{id}/stop       ActivityService.stopActivity(id)     // @Transactional
  ├── load activity → 404 if missing
  ├── if status == COMPLETED → 400 (already done)
  ├── status = COMPLETED;  endTime = now
  ├── averageSpeedMps = movingTimeSeconds > 0 ? distanceMeters/movingTimeSeconds : 0   (finalize)
  ├── activityRepository.save(...)
  └── eventPublisher.publishEvent(new ActivityCompletedEvent(this, id, userId))
        → consumed AFTER_COMMIT by LeaderboardService (path (d)); returns 200 immediately
```

### (c) FRIEND FEED + LIVE-LOCATION READ PATH

Both feeds are **read-time fanout** over a small friend set — no pre-materialized feed to write on the ingest path.

```
GET /api/users/{userId}/feed?page=0&size=20        (completed activities)

FeedController.getFeed → FeedService.getFriendFeed(userId, page, size)
  ├── friendIds = friendshipService.getFriendIds(userId)   → FriendshipRepository.findByUserId (indexed)
  │     if empty → return []  (no friends → empty feed)
  ├── activityRepository.findByUserIdInAndStatusOrderByStartTimeDesc(friendIds, COMPLETED, page)
  │     → indexed on (user_id, start_time); newest first
  └── map each Activity → activityService.toResponse (resolves userName) → List<ActivityResponse>

GET /api/users/{userId}/feed/live                  (friends recording right now)

FeedController.getLiveFeed → FeedService.getLiveFriendActivities(userId)
  ├── friendIds = friendshipService.getFriendIds(userId);  if empty → []
  ├── active = activityRepository.findByUserIdInAndStatusOrderByStartTimeDesc(friendIds, ACTIVE, 0..LIVE_SCAN_LIMIT=100)
  └── FOR each active activity:
        location = liveLocationCacheService.getLocation(activity.id)     ← Redis GET strava:live:{id}
                    .orElseGet(() → fallbackLocation(activity))          ← DEGRADE to persisted last* running-state
        add to result

GET /api/activities/{id}/live                      (single beacon; LiveActivityService)
  └── liveLocationCacheService.getLocation(id)
        .orElseGet(() → load activity (404 if missing) → fallbackLocation from persisted running-state)
```

The live cache entry is written on every GPS batch (path (a)), so a spectator sees a position at most one sync-interval stale. If the entry expired (TTL 300s between batches) or Redis is down, the fallback serves the activity's last persisted `last{Lat,Lng}` + `distance/elapsed` — the endpoint never fails on a cache miss.

### (d) LEADERBOARD: population on completion + read path

Segment matching runs **asynchronously after the stop transaction commits** — the same fanout-on-write discipline used for the feed elsewhere in the repo — so "stop activity" returns instantly and matching reads only committed route points.

```
POPULATION (triggered by ActivityCompletedEvent from path (b)):

LeaderboardService.onActivityCompleted(event)
    // @Async  +  @TransactionalEventListener(AFTER_COMMIT)  +  @Transactional
  ├── activity = activityRepository.findById(event.activityId);  if gone → log + return
  ├── points = routePointRepository.findByActivityIdOrderBySequenceAsc(activityId);  if empty → return
  ├── segments = segmentRepository.findByActivityType(activity.type)   (RUN matches only RUN segments)
  └── FOR each segment:
        ├── radius = segment.matchRadiusMeters (>0) else defaultMatchRadiusMeters(30)
        ├── startIdx = firstPointWithinRadius(points, 0, segment.start, radius)          → skip if none
        ├── endIdx   = firstPointWithinRadius(points, startIdx+1, segment.end, radius)   → skip if none
        ├── require end.recordedAt AFTER start.recordedAt
        ├── elapsed = end.recordedAt − start.recordedAt (seconds)
        ├── save SegmentEffort{segmentId, activityId, userId, elapsed, achievedAt=now}   (durable source of truth)
        └── leaderboardCacheService.addEffort(segmentId, userId, elapsed)
              → Redis: only ZADD if new or strictly faster than current score (keeps best-time-per-user)

READ:

GET /api/segments/{segmentId}/leaderboard?limit=10
LeaderboardController → LeaderboardService.getLeaderboard(segmentId, limit)
  ├── segment = segmentRepository.findById(segmentId)   → 404 if missing
  ├── effectiveLimit = limit>0 ? limit : defaultLimit(10)
  ├── cached = leaderboardCacheService.top(segmentId, effectiveLimit)   ← ZRANGE 0..limit-1 (ascending = fastest first)
  ├── if cached non-empty: rank 1..N → LeaderboardEntry (resolveName), DONE
  └── else COLD-CACHE / REDIS-DOWN FALLBACK:
        ├── efforts = segmentEffortRepository.findBySegmentIdOrderByElapsedTimeSecondsAsc(segmentId, 0..limit*3)
        ├── dedup to best-per-user via LinkedHashMap.putIfAbsent (list already ascending → first seen = best)
        ├── for top effectiveLimit: leaderboardCacheService.addEffort(...) to WARM the cache, build entry (with activityId)
        └── return ranked entries
```

---

## Why Key Decisions Were Made in Code

### Why the idempotent sequence guard (`GpsIngestionService` + `Activity.lastSequence`)

The single most important line in the write path:

```java
if (point.getSequence() <= activity.getLastSequence()) {
    continue;   // already applied — retried/duplicate batch
}
```

Offline sync means a phone may resend a batch it isn't sure the server received (dropped ACK, app killed mid-sync, reconnect). Without a guard, replaying a batch would double-count distance and time, corrupting the athlete's stats. The client assigns a **monotonic sequence** per point; the server records the highest applied sequence on the activity row and skips anything at or below it. Combined with `RoutePoint`'s `UNIQUE(activity_id, sequence)`, at-most-once application is guaranteed at both the logic and storage layers. The batch is also sorted by sequence first, so a merged offline buffer that arrives out of order still applies correctly and the cursor advances monotonically.

### Why running aggregates on the Activity row (not compute-on-read)

`ingestBatch` folds each point into `distanceMeters`, `elapsedTimeSeconds`, `movingTimeSeconds`, `elevationGainMeters` incrementally, carrying a `last*` cursor (previous sample) so each batch only needs the delta from the previous point — never a re-scan of the whole route:

```java
double d = statsCalculator.haversineMeters(lastLat, lastLng, point.lat, point.lng);
activity.setDistanceMeters(activity.getDistanceMeters() + d);
```

Re-summing an entire route (potentially thousands of points) on every batch would make the write path O(route length) and defeat the scale goal. Incremental folding keeps it O(batch size) — a handful of arithmetic ops — which is what makes ~1M append-writes/sec across 10M concurrent activities tractable, and it's shardable by activity/user because nothing is shared across activities. `movingTimeSeconds` only accumulates when inter-point speed clears `movingSpeedThresholdMps` (0.5 m/s), so time spent stopped at lights/rests doesn't inflate the moving average. This mirrors the same math the phone runs locally, so server-side stats **reconcile** with the client's authoritative live numbers.

### Why the AFTER_COMMIT @Async event for leaderboards

`stopActivity` publishes `ActivityCompletedEvent` inside its transaction; `LeaderboardService.onActivityCompleted` consumes it with:

```java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional
public void onActivityCompleted(ActivityCompletedEvent event) { ... }
```

- **AFTER_COMMIT**: segment matching re-reads the activity and its route points from the DB. A plain listener could fire before the stop transaction committed, so the async thread might see a not-yet-committed activity or a partial route — a read-before-commit race. Binding to `AFTER_COMMIT` guarantees the finalized activity and all its points are durably visible first.
- **@Async**: replaying every route point against every same-type segment is real CPU work. Running it inline would make the athlete wait on "stop." Off the request thread, `stopActivity` returns 200 immediately and matching proceeds in the background — exactly the fanout-on-write discipline (cheap write path, expensive work deferred).

### Why graceful Redis degradation everywhere

Both cache services wrap every Redis call in try/catch and return a safe default (`Optional.empty()`, empty list, or no-op). Availability is prioritized over consistency, so Redis is a **performance layer, never a single point of failure**:

- `LiveLocationCacheService.updateLocation` failing during ingest is a no-op — the GPS write still commits (the beacon just goes stale).
- `getLocation` returning empty (miss or outage) makes `FeedService`/`LiveActivityService` fall back to the activity's persisted `last*` running-state.
- `LeaderboardCacheService.top` returning empty routes `getLeaderboard` to the `segment_efforts` table (the durable source of truth), which then **warms** the cache for the next reader.
- `DataInitializer` wraps its whole seed in try/catch (and the cache warm is best-effort) so the app boots even with Redis down.

The p99 latency target may slip during a Redis outage, but every request still succeeds against the primary datastore.

### Why the leaderboard keeps only best-time-per-user

`LeaderboardCacheService.addEffort` reads the current score before writing:

```java
Double current = redis.opsForZSet().score(key, userId);
if (current == null || elapsedTimeSeconds < current) {
    redis.opsForZSet().add(key, userId, elapsedTimeSeconds);   // ZADD overwrites the member's score
}
```

A leaderboard ranks athletes, not attempts — a member should appear once at their fastest time. Using `userId` as the ZSET member (score = elapsed seconds, ascending = fastest first) means a slower repeat effort is ignored and a faster one overwrites in place. The DB fallback enforces the same rule differently: efforts come back sorted ascending, and `LinkedHashMap.putIfAbsent` keeps the first (fastest) row seen per user.

### Why segment matching is scoped by ActivityType and uses a radius

`segmentRepository.findByActivityType(activity.type)` means a RUN is only matched against RUN segments. A running effort and a cycling effort on the same physical road are not comparable, so the leaderboards are separated at query time. The geometric match (`firstPointWithinRadius` for start then a later point for end, both within `matchRadiusMeters`) is deliberately **naive** — GPS noise is absorbed by the radius, and requiring the end point to be strictly after the start guarantees a positive elapsed time. Production would replace this with GPS map-matching against a spatial index; the code comment flags this as the intended upgrade.

### Why read-time fanout for feeds (no pre-materialized feed)

`FeedService` resolves the friend set on every read and queries the activities table directly, rather than pushing each completed activity into followers' feeds on write:

```java
List<String> friendIds = friendshipService.getFriendIds(userId);
activityRepository.findByUserIdInAndStatusOrderByStartTimeDesc(friendIds, COMPLETED, page);
```

Strava's social graph is symmetric friends (mutual follows), so friend counts are small — there are no million-follower celebrities to protect against. With a small fan-in, an indexed `(user_id, start_time)` query across the friend set is cheap and avoids the write amplification of materializing a feed on every activity completion. (This is the opposite trade-off from the newsfeed project's hybrid fanout, and it's the right one for this graph shape.)

### Why the symmetric friend graph stores both directions

`DataInitializer` inserts `Alice→Bob` and `Bob→Alice` as two rows. Storing both directions turns "who are my friends" into a single indexed read on `user_id` (`FriendshipRepository.findByUserId`) — no OR across two columns, no second query. Feed and live-view reads fan out from this set, so keeping it a one-column lookup matters for read latency.

### Why the async pool uses CallerRunsPolicy

`AsyncConfig.stravaAsyncExecutor` is bounded (core 4, max 16, queue 500) with:

```java
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
```

If a burst of activity completions saturates the queue, new segment-matching tasks run on the calling (request) thread instead of throwing `RejectedExecutionException`. This applies natural backpressure — completions slow down rather than silently dropping leaderboard work. An `AsyncUncaughtExceptionHandler` logs any uncaught async failure with the method name for alerting. It's exposed as the default executor (`getAsyncExecutor`) so the plain `@Async` listener runs on it without a qualifier.

### Why `ActivityStatsResponse` returns `lastSequence`

The post-ingest response echoes the server's `lastSequence`. Since the client is the source of truth and buffers offline, it needs to know exactly which points the server has durably applied so it can safely drop them from its local buffer and resume syncing from the next sequence — the reconciliation handshake that makes offline sync reliable.

---

## Dependency Flow

```
ActivityController
  ├── ActivityService
  │     ├── UserService            (getUserEntity — validate owner; resolveName)
  │     ├── ActivityRepository     (save, findById, list)
  │     ├── RoutePointRepository   (route read)
  │     └── ApplicationEventPublisher (ActivityCompletedEvent on stop)
  │                 │
  │                 └── LeaderboardService.onActivityCompleted  (@Async AFTER_COMMIT)
  │                        ├── ActivityRepository / RoutePointRepository / SegmentRepository
  │                        ├── SegmentEffortRepository   (durable effort rows)
  │                        ├── StatsCalculator           (haversine proximity)
  │                        └── LeaderboardCacheService    (Redis ZADD best-time)
  ├── GpsIngestionService
  │     ├── ActivityRepository     (load + save running aggregates)
  │     ├── RoutePointRepository   (append points)
  │     ├── StatsCalculator        (haversine distance)
  │     └── LiveLocationCacheService (Redis SET beacon, best-effort)
  └── LiveActivityService
        ├── LiveLocationCacheService (Redis GET, cache-first)
        └── ActivityRepository       (persisted running-state fallback)

FeedController
  └── FeedService
        ├── FriendshipService        (getFriendIds)
        ├── ActivityRepository       (findByUserIdInAndStatus... — completed + active)
        ├── ActivityService          (toResponse)
        └── LiveLocationCacheService (live beacons + fallback)

LeaderboardController
  └── LeaderboardService
        ├── SegmentRepository / SegmentEffortRepository
        ├── LeaderboardCacheService  (top; warm on fallback)
        └── UserService              (resolveName)

UserController
  └── UserService → UserRepository

Config: RedisConfig (StringRedisTemplate, ObjectMapper) · AsyncConfig (stravaAsyncExecutor) · DataInitializer (seed)
```

---

## Key Invariants

1. **A GPS point is applied at most once.** Enforced by the `sequence <= lastSequence` guard in `GpsIngestionService` plus `RoutePoint`'s `UNIQUE(activity_id, sequence)`. Retried/duplicate batches never double-count.
2. **`lastSequence` is monotonic non-decreasing.** Only advanced when a strictly-newer sequence is applied; the batch is sorted ascending first so the cursor never regresses.
3. **Running aggregates are computed incrementally, never re-scanned.** `distance/elapsed/moving/elevation` accumulate via the `last*` cursor delta; `averageSpeedMps = distance/movingTime` (0 when movingTime is 0) is recomputed each batch and finalized on stop.
4. **No points after COMPLETED.** `ingestBatch` rejects a completed activity with `IllegalStateException` → 400; completed activities are immutable.
5. **Lifecycle transitions are validated.** ACTIVE→PAUSED→ACTIVE→COMPLETED (or ACTIVE→COMPLETED); any illegal transition raises `IllegalStateException` → 400.
6. **Leaderboard matching runs only on committed data, off the request thread.** Enforced by `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` on `onActivityCompleted`.
7. **A segment leaderboard holds one entry per athlete, at their best time.** Enforced by `LeaderboardCacheService.addEffort` (write only if faster) and by `putIfAbsent` dedup in the DB fallback.
8. **Segments match only same-type activities.** `findByActivityType` scopes matching; RUN efforts never enter RIDE leaderboards.
9. **Redis is never on the critical path for correctness.** Every cache op is try/catch-wrapped; live reads fall back to persisted running-state, leaderboard reads fall back to `segment_efforts` (then warm the cache), and cache writes are best-effort no-ops on failure.
10. **The friend feed is empty, never wrong, for a user with no friends.** `getFriendFeed` / `getLiveFriendActivities` short-circuit to an empty list when the friend set is empty.
11. **Missing users degrade names, not requests.** `UserService.resolveName` returns the raw id if a user row is absent, so feed/leaderboard responses never fail on a dangling reference.
```
