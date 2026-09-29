# Strava Activity Tracking — Interview Study Guide

## 30-Second Pitch
"Strava records runs and rides for 100M+ athletes and must work offline in the mountains, show accurate live stats mid-activity, and scale to 10M concurrent activities. The core move: the **phone is the source of truth while recording**. It samples GPS, computes distance/time/elevation locally, buffers points offline, and syncs them to the server in **idempotent, ordered batches** keyed by a client-assigned monotonic sequence. The server just appends points and folds each into a running aggregate. That one decision gives offline support, accurate live stats, and a write path cheap enough to shard by activity across millions of concurrent recordings. Reads — feeds, activity detail, leaderboards — are cache-friendly; live friend tracking rides a short-TTL Redis cache and segment leaderboards are Redis sorted sets populated async after an activity completes."

## Key Numbers (derive these cold)
- **~100M+ users**, target **10M concurrent activities** at peak.
- A 1-hour activity at **1 sample/sec ≈ 3,600 points**. Raw point ≈ 40 bytes → **~144 KB/activity** raw, far less as an encoded polyline.
- **If clients streamed every GPS tick:** 10M concurrent × 1 write/sec = **10M writes/sec** — brutal.
- **Batched instead:** sync a small batch every ~10s → 10M / 10 = **~1M append-writes/sec**, each a tiny append + a few arithmetic ops, sharded by activity. An order of magnitude cheaper and horizontally shardable.
- **Storage/day:** if ~1–2M activities complete per day × ~50–150 KB → tens to low-hundreds of GB/day of route data (cold-storable / compressible).
- **Live location TTL:** ~300s (code default) — hot entries auto-expire, so the "who's out right now" query never scans the DB.
- **Read side** (feeds, activity detail, leaderboards) is cache-friendly and dwarfed by the write path in engineering difficulty.

## Critical Decision: Client as Source of Truth While Recording
The whole design hinges on **who owns the activity data during recording**. Availability >> consistency here: the app must keep working with zero network, and live on-device stats must stay accurate regardless of sync state.

### Option A — Stream every GPS tick to the server (server-authoritative)
- Phone sends each GPS sample as it fires; server computes stats and streams them back.
- ❌ Dies with no network — no offline recording in remote areas (the whole point of Strava).
- ❌ 10M concurrent × 1 write/sec = 10M writes/sec, plus a live connection per activity.
- ❌ Live stats depend on a round trip; laggy/unreliable UI.

### Option B — Batched, client-authoritative sync (Chosen)
- Phone samples GPS, computes distance/time/elevation **locally** for the live UI, buffers points offline, and syncs them in **batches** ordered by a **client-assigned monotonic sequence**.
- Server appends points and folds each into a running aggregate; it **reconciles** the same stats using the identical haversine formula, but the client stays authoritative for the live screen.
- ✅ Works fully offline; syncs opportunistically when a network appears.
- ✅ Idempotent + ordered → retries after flaky network or app restart never double-count.
- ✅ Write path is a tiny append, shardable by activity/user → scales to 10M concurrent.
- ❌ Server stats lag the client until the next sync (acceptable — client owns the live truth).

### Option C — Server-side reconstruction from raw points only
- Store raw points, recompute all stats on read.
- ✅ Simple write.
- ❌ Every read re-processes thousands of points; live stats and leaderboards get expensive.
- ❌ Still doesn't answer the offline question — that's a client concern regardless.

**Why B wins:** offline support, accurate live stats, and cheap shardable writes all fall out of the single "client is authoritative + batched idempotent sync" decision. Running aggregates on the activity row mean reads are O(1), not O(points).

## How Idempotent Batched Sync Works (the mechanic to name)
- Each point carries a **client-assigned monotonic `sequence`**; the activity row tracks `lastSequence` (highest applied).
- On ingest: sort the batch by sequence, **skip any point with `sequence <= lastSequence`**, append the rest, and advance a running-state cursor (`lastLat/lastLng/lastElevation/lastPointTime`) to compute each incremental delta.
- A **unique constraint on `(activity_id, sequence)`** enforces at-most-once storage as a second line of defense.
- Result: re-submitting an already-applied batch is a **no-op** — safe retries with no double-counting of distance/time.

## Common Questions

**Q: "How does offline recording + sync stay correct under retries?"**
A: Client buffers points locally with monotonic sequences. Sync sends ordered batches; server skips `sequence <= lastSequence` and dedups via the `(activity_id, sequence)` unique key. Retrying a partially-applied batch just re-applies the tail. Distance/time are folded incrementally from a running cursor, so re-runs are exactly-once in effect.

**Q: "How do you scale to 10M concurrent activities?"**
A: Client-as-source-of-truth + batching makes each write a tiny append plus a few arithmetic ops. Batching every ~10s instead of streaming every tick cuts 10M writes/sec to ~1M. Activities are independent, so shard the write path by activity/user id — no cross-shard coordination. Running aggregates on the row keep reads O(1).

**Q: "How does live friend sharing (Beacon) work?"**
A: Every GPS batch best-effort writes the latest position + running aggregates to a short-TTL Redis key (`strava:live:{activityId}`, TTL ~300s). The friend live feed resolves a user's friends, finds their `ACTIVE` activities, and reads those hot keys; on a miss (TTL expiry or Redis down) it degrades to the activity's last persisted running-state. **Production upgrade: push over WebSocket** instead of poll + cache read.

**Q: "How are segment leaderboards built?"**
A: On `stopActivity` we publish an `ActivityCompletedEvent`; an `@Async @TransactionalEventListener(AFTER_COMMIT)` handler replays the route, and for each candidate segment checks whether the athlete passed within a match radius of the segment start then end, records a `SegmentEffort`, and pushes the elapsed time into a **Redis sorted set** (`strava:leaderboard:{segmentId}`, member=userId, score=elapsed, best-time-only). Reads range the ZSET ascending (fastest first); cold cache falls back to the `segment_efforts` table (indexed on `(segment_id, elapsed_time_seconds)`), dedups to each athlete's best, and warms the cache. Matching runs after commit so "stop" returns immediately.

**Q: "Is the naive start/end proximity segment match good enough?"**
A: No — it's the demo simplification. It can false-match crossing paths and ignores route shape. Production uses **GPS map-matching against a spatial index** (snap points to roads, match the full segment geometry).

**Q: "GPS is noisy — how accurate are the stats?"**
A: Haversine between consecutive samples over-counts on jitter and under-counts on straightaways; elevation from GPS is especially noisy. Mitigations: smoothing/Kalman filtering, a **moving-speed threshold** (below ~0.5 m/s counts as stopped, so `movingTime` excludes traffic lights), barometric altimeter for elevation, and map-matching. The client computes the authoritative live numbers; the server reconciles with the same formula.

**Q: "What about consistency of server vs client stats?"**
A: Deliberately eventually-consistent. The client is authoritative for the live UI; the server stats catch up on each sync and are final once the activity is `COMPLETED`. Availability was chosen over strict consistency.

## Trade-offs
| Factor | Stream every tick (server-auth) | Batched client-authoritative (Chosen) | Server-side reconstruction |
|--------|--------------------------------|----------------------------------------|----------------------------|
| Offline support | None | Full (buffer + sync) | None |
| Live-stat accuracy | Round-trip dependent | Instant (on-device) | N/A during recording |
| Writes @ 10M concurrent | ~10M/sec + live conns | ~1M/sec tiny appends, sharded | Low writes, heavy reads |
| Read cost | Low | O(1) running aggregates | O(points) per read |
| Retry safety | Ad hoc | Idempotent by sequence | Idempotent (raw append) |
| Consistency | Strong | Eventual (client authoritative) | Eventual |

## Red Flags vs Good Answers
❌ "Stream each GPS point to the server in real time" → breaks offline, 10M writes/sec.
❌ "Recompute stats from all points on every read" → O(points) reads, expensive leaderboards.
❌ "Server owns the live stats" → laggy UI, fails with no network.
❌ "Block the stop request on segment matching" → slow saves; do it async after commit.
❌ "Poll the DB for who's live right now" → hammers the primary; use a TTL cache.
✅ "Client is source of truth; buffer offline; sync idempotent ordered batches keyed by monotonic sequence."
✅ "Running aggregates on the activity row, shard the write path by activity id."
✅ "Redis sorted sets for leaderboards, populated async after activity completes, DB fallback."
✅ "Short-TTL Redis live-location cache for friend tracking; WebSocket push in production."

## Stack & Surface Area
- **Stack:** Spring Boot 3.2 · H2 (in-memory) · Redis · Spring `@Async`.
- **Entities:** `User`, `Friendship`, `Activity` (running aggregates + `lastSequence` guard), `RoutePoint` (unique `(activity_id, sequence)`), `Segment`, `SegmentEffort`.
- **Key endpoints:**
  - Lifecycle: `POST /api/activities`, `.../{id}/pause`, `/resume`, `/stop`
  - Sync: `POST /api/activities/{id}/points` (idempotent batch ingest)
  - Read: `GET /api/activities/{id}`, `.../route`, `.../live`, `GET /api/users/{userId}/activities`
  - Feed: `GET /api/users/{userId}/feed`, `.../feed/live`
  - Segments: `GET /api/segments`, `GET /api/segments/{segmentId}/leaderboard`
