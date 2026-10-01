# Strava Activity Tracking — Interview Study Guide

## 30-Second Pitch
"Strava records runs and rides for 100M+ athletes and must work offline in the mountains, show accurate live stats mid-activity, and scale to 10M concurrent activities. The core move: the **phone is the source of truth while recording**. It samples GPS, computes distance/time/elevation locally, buffers points offline, and syncs them to the server in **idempotent, ordered batches** keyed by a client-assigned monotonic sequence. The server just appends points and folds each into a running aggregate. That one decision gives offline support, accurate live stats, and a write path cheap enough to shard by activity across millions of concurrent recordings. Reads — feeds, activity detail, leaderboards — are cache-friendly; live friend tracking rides a short-TTL Redis cache and segment leaderboards are Redis sorted sets populated async after an activity completes."

## Key Numbers (derive these cold)
- **~100M+ users**, target **10M concurrent activities** at peak.
- A 1-hour activity at **1 sample/sec ≈ 3,600 points**. Raw point ≈ 40 bytes → **~144 KB/activity** raw, far less as an encoded polyline.
- **If clients streamed every GPS tick:** 10M concurrent × 1 write/sec = **10M writes/sec** — brutal.
- **Batched instead:** sync a small batch every ~10s → 10M / 10 = **~1M append-writes/sec**, each a tiny append + a few arithmetic ops, sharded by activity. An order of magnitude cheaper and horizontally shardable.
- **Storage:** route points dominate — store the **encoded polyline (~5 KB/activity)**, not the ~144 KB raw. ~100M activities/year × ~5 KB ≈ **~0.5 TB/year** of route data (append-only, compressible, cold-storable).
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

## Cloud Infrastructure & Sizing (AWS ↔ GCP)

> How you'd actually deploy this. Every AWS service below is paired with its GCP equivalent, and every instance count is derived from this design's own back-of-the-envelope numbers.

### Building blocks this design needs
- **Relational DB** — the system of record for `activities` (running aggregates + `lastSequence` guard), `route_points` (append-only, unique `(activity_id, sequence)`), `users`, `friendships`, `segments`, `segment_efforts`; every table shards cleanly by `activity_id`/`user_id`.
- **Key-value / cache** — Redis backs the Beacon live-location entries (`strava:live:{activityId}`, String + 300s TTL) and the segment leaderboards (`strava:leaderboard:{segmentId}`, sorted set, best-time-only), both rebuildable from the DB.
- **Task queue / async worker** — segment matching runs off the request thread after the completing transaction commits (`@Async @TransactionalEventListener(AFTER_COMMIT)` today), so `stop` returns immediately and matching only ever reads committed data.

### Managed-service equivalents
| Building block | In this repo (dev) | AWS | GCP | When to reach for it |
|----------------|--------------------|-----|-----|----------------------|
| Relational DB | H2 in-memory | RDS / Aurora PostgreSQL (sharded); route points aged to S3 + Athena | Cloud SQL for PostgreSQL / AlloyDB (sharded); route points aged to GCS + BigQuery | System of record; shard hot/recent by `activity_id`, age immutable route points to a columnar cold store |
| Key-value / cache | Local Redis (optional) | ElastiCache for Redis (cluster mode) | Memorystore for Redis Cluster | Short-TTL live-location reads + O(log N) leaderboard sorted sets; a miss degrades to the DB |
| Task queue / async worker | In-JVM Spring `@Async` `ThreadPoolTaskExecutor` (`CallerRunsPolicy`) | SQS + worker fleet (or EventBridge event bus) | Pub/Sub + worker fleet (or Eventarc event bus) | Decouple post-completion segment matching from the ingest/stop path; absorb morning/evening completion bursts |

### Compute & capacity sizing (from our BOTE numbers)
Driving numbers for this design (from the Key Numbers section + README capacity):
- **10M concurrent activities** at peak.
- **~1M append-writes/sec** — each client flushes a batch every ~10s (10M ÷ 10s). At 1 Hz sampling each batch carries ~10 points, so the DB absorbs ~10M route-row inserts/sec (derived).
- **~0.5 TB/year** of encoded route data (~5 KB × ~100M activities/year); live-location TTL ~300s.
- **~2,800 activity completions/sec** (derived: 10M concurrent ÷ ~3,600s average activity duration) — the driver for the async segment-matching fleet.

| Tier | Driving number | Sizing logic | AWS | GCP |
|------|----------------|--------------|-----|-----|
| App / API tier | ~1M ingest req/sec (one batch/activity/~10s) | ~1M req/sec ÷ ~5K req/sec per node ≈ 200 nodes + ~50% headroom ≈ **~300** | c7g.2xlarge (c7gn if NIC-bound) | c3-standard-8 / n2-standard-8 |
| Primary DB (sharded) | ~1M batch-writes/sec (~10M route inserts/sec) | ~1M writes/sec ÷ ~15K write-txns/sec per shard primary ≈ ~67 + headroom ≈ **~100 shard primaries**; partition `route_points` by `activity_id`, age to cold store | db.r7g.2xlarge (Aurora/RDS PostgreSQL) | Cloud SQL (high-mem) / AlloyDB |
| Read replicas | Feeds/detail reads (cache-served, dwarfed by writes) | 1–2 replicas per shard for read scale + HA ≈ **~100–200 replicas** | db.r7g.2xlarge read replicas | Cloud SQL / AlloyDB read pool |
| Cache (Redis) | ~1M live writes/sec + live/feed reads; 10M live entries | ~1M+ ops/sec ÷ ~100K ops/sec per node ≈ ~10 + headroom ≈ **~15 nodes** (cluster mode); working set 10M × ~300 B ≈ ~3 GB + leaderboard ZSETs | cache.r7g.large (ElastiCache cluster) | Memorystore for Redis Cluster (Standard) |
| Async worker fleet (segment matching) | ~2,800 completions/sec, each replaying ~3,600 points | ~2,800 ÷ ~100 jobs/sec per node ≈ ~28 + headroom ≈ **~40 nodes**; bursty → autoscale | c7g.xlarge (or Lambda/Fargate) | c3-highcpu-8 (or Cloud Run jobs) |

### Load balancing & edge
- **L7 application LB** fronts the REST API (batch ingest + reads) for TLS termination and path/host routing: **AWS ALB** ↔ **GCP Global External Application Load Balancer**.
- **Global anycast** for a worldwide athlete base so mobile clients hit the nearest region: **AWS Global Accelerator** ↔ GCP's global LB is anycast by default. Use an **L4 NLB** (**AWS NLB** ↔ **GCP External passthrough Network LB**) only if you need raw pass-through throughput.
- **CDN** fronts the immutable read path — a completed activity's route never changes, so cache `GET .../route` and activity detail at the edge: **AWS CloudFront** ↔ **GCP Cloud CDN**.
- **Long-lived connections:** today Beacon is cache + polled read, so **no sticky sessions are needed** — any node serves any request. The production WebSocket/SSE push upgrade would need WebSocket-capable L7 LBs (ALB and GCP's LB both support it); keep connections stateless by externalizing live position to Redis so no client affinity is required.

### VMs vs containers vs serverless — the call
Run the app/API and data tiers as **containers on managed Kubernetes — AWS EKS ↔ GCP GKE** (ECS/Cloud Run are fine substitutes): the ingest path is steady, high-volume (~1M req/sec) and horizontally sharded, where always-on containers beat per-invocation serverless on cost and dodge JVM cold-start latency, while giving cleaner rollouts and autoscaling than raw EC2/Compute Engine VMs. The one tier that is genuinely spiky — the **segment-matching worker**, which fires in morning/evening completion bursts — is the best serverless candidate: run it as **AWS Lambda (SQS-triggered) ↔ GCP Cloud Run jobs (Pub/Sub-triggered)** so it scales to zero between peaks.
