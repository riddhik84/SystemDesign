# GoPuff-like Delivery — Interview Study Guide

Everything you need to drive this design in a 45-minute interview.

---

## 1. Clarifying questions (ask these first)
- **Delivery radius / SLA:** Is delivery defined by a fixed radius or a drive-time? (We use a
  60-mile straight-line proxy for the 1-hour window.)
- **Partial fulfillment:** If a basket can't be fully satisfied, do we partial-fill or reject
  the whole order? (Answer drives everything → **reject whole order**.)
- **Single vs split fulfillment:** Can one order ship from multiple DCs? (We say **single DC**.)
- **Consistency model:** Strong consistency for orders (no double-sell)? Eventual OK for
  availability? (Yes / yes.)
- **Reservation semantics:** Do we hold stock for pending orders, or only decrement on
  shipment? (We **reserve**.)
- **Scale numbers:** orders/day, searches per buyer, conversion rate, #DCs, #items.

---

## 2. Numbers cold
- **20K availability QPS** (10M orders × ~10 searches ÷ ~5% conversion).
- **~115 orders/sec** average (10M/day); plan ~5–10× peak (~1K/sec).
- **Read:write ≈ 175:1** → cache + read replicas for availability.
- **10K DCs, 100K items** → up to **1B inventory rows** (sparse in practice).
- **Availability p99 < 100 ms** (Redis, 60s TTL).
- Storage: full inventory ≈ 60 GB; orders ≈ 1 TB/year.

---

## 3. Core entities
`items`, `distribution_centers`, `inventory(item, dc, quantity, reserved_quantity)`,
`orders(fulfilling_dc_id, status)`, `order_items(price_at_order)`.

Headline: **`available = quantity - reserved_quantity`**, with DB CHECK
`0 <= reserved_quantity <= quantity`.

---

## 4. API design
- `GET /availability?latitude&longitude&items=CSV&page` → list of
  `{itemId, itemName, available, quantity, dcId}`. 422 if no DC serves the location.
- `POST /orders` `{userId, lat, lon, items:[{itemId, quantity}]}` → 201 CONFIRMED, 409 if any
  item unavailable, 422 if no DC, 400 on validation.

---

## 5. THE central decision: preventing double-booking

You will be graded on this. Walk through **three** approaches and recommend the third.

**Approach A — application-level check-then-update (WRONG).**
```
read available; if enough -> write reserved += qty
```
Two requests both read "1 available", both think they can buy → **lost update / double-sell**.
The classic race. Never propose this without locking.

**Approach B — optimistic locking (version column + retry).**
Add a `version`; on update, `WHERE version = :v`; if 0 rows updated, someone else won → retry.
Correct, lock-free, great under **low contention**. Problem: popular items are **hot rows**;
N concurrent buyers cause N−1 retries → thrashing and tail-latency spikes exactly when it
matters most.

**Approach C — SERIALIZABLE + `SELECT ... FOR UPDATE` (RECOMMEND).**
Run the order in a SERIALIZABLE transaction; lock each inventory row with `FOR UPDATE`,
re-check availability under lock, then reserve.
- The row lock serializes the hot-row contended case deterministically (one proceeds, others
  briefly queue — no retry storm).
- SERIALIZABLE is the correctness backstop for the multi-row "can this DC fulfill the whole
  order" reasoning.
- Orders + inventory in **one DB** → one local ACID transaction, **no distributed txn**.

Say the phrase: *"belt-and-suspenders — pessimistic row lock for the contended common case,
SERIALIZABLE as the correctness backstop, all in a single local transaction because orders
and inventory live in the same database."*

---

## 6. Architecture diagram (draw this)
```
                       ┌── READ (≈20K QPS) ──┐         ┌── WRITE (≈115 OPS) ──┐
 Client ─ GET /avail ─>│ Controller          │  Client─>│ POST /orders         │
                       │ Service             │          │ OrderService         │
                       │  Redis GET (HIT→ret)│          │  @Txn SERIALIZABLE   │
                       │  miss:              │          │  nearby DCs          │
                       │   NearbyDc(Haversine│          │  FOR UPDATE rows     │
                       │   InventoryRepo(rep)│          │  reserve += qty      │
                       │   aggregate         │          │  INSERT order        │
                       │   Redis SET (60s)   │          │  evict cache         │
                       └─────────────────────┘          └──────────────────────┘
   Redis (cache)        Postgres replicas (reads)     Postgres PRIMARY (writes)
```

---

## 7. Common follow-up Q&A
- **What if a DC runs out mid-fulfillment?** The order txn re-checks `available` under
  `FOR UPDATE`; if short, it tries the next-nearest DC, and if none can fulfill the whole
  order it rolls back and returns 409. No reservation leaks.
- **How do you scale the order DB?** Reads to replicas, writes to primary; partition by
  `region_code` (zipcode prefix) so each region is an independent transactional shard.
- **What if Redis is down?** Graceful degradation: cache reads return empty (→ DB fallthrough),
  writes/evicts no-op. Latency rises, correctness unaffected — the DB is the source of truth.
- **Why not just decrement `quantity`?** Reservations are reversible (cancellations) and
  auditable, and DB constraints make overselling impossible at the storage layer.
- **Why 60s TTL?** Bounds staleness; order path re-validates strongly so a stale "available"
  can never cause a double-sell, only a clean 409.
- **Why single-DC fulfillment?** One delivery trip; splitting complicates routing, pricing,
  and refunds.

---

## 8. What interviewers want to hear
- You identified the **read:write skew** and put cache + replicas on the read path.
- You can articulate the **double-sell race** and fix it precisely (FOR UPDATE + SERIALIZABLE).
- You keep **orders + inventory in one DB** to avoid distributed transactions.
- You separate **physical stock** from **reserved** and compute availability.
- You make availability **eventually consistent but cheap**, and orders **strongly consistent**.
- You name the **graceful-degradation** story for Redis.

---

## 9. Common mistakes
- Using **READ COMMITTED** for orders and assuming it prevents double-sell — it does not.
- Forgetting **`SELECT ... FOR UPDATE`** (or doing check-then-update without a lock).
- Allowing **partial fulfillment** silently (must be a product decision; here it's rejected).
- Putting orders and inventory in **separate services** and hand-waving the distributed txn.
- Caching availability with **per-exact-coordinate keys** (cache never hits) — round coords.
- No **cache eviction / TTL** story → unbounded staleness.
- Storing `available` as a column (drifts) instead of computing it.

---

## 10. Extension questions
- **Driver assignment & ETA:** dispatch service consuming an `order.confirmed` event; assign
  nearest available driver; expose ETA via tracking service.
- **Real-time tracking:** websocket/SSE channel keyed by orderId; driver app pushes GPS.
- **Payments:** authorize on order (reserve), capture on dispatch; saga to release the
  inventory reservation if payment fails.
- **Cancellations:** transition order to CANCELLED and `reserved_quantity -= qty` in a txn;
  evict cache.
- **Surge / dynamic radius:** shrink delivery radius when DCs are overloaded.
- **Recommendations / substitutions:** suggest in-stock alternatives when an item is OOS.

---

## 11. One-page cheat sheet
- **Endpoints:** `GET /availability` (cache, replicas), `POST /orders` (primary, SERIALIZABLE).
- **Numbers:** 20K avail QPS, 115 OPS, 175:1 read:write, 1B inventory rows, p99<100ms.
- **Data:** inventory = `quantity`, `reserved_quantity`; `available = quantity - reserved`.
- **Double-sell fix:** SERIALIZABLE txn + `SELECT ... FOR UPDATE` per row, re-check, reserve.
- **Atomicity:** orders + inventory in one DB → one local transaction; no 2PC.
- **All-or-nothing:** whole order from one nearest capable DC, else 409.
- **Availability:** Redis key `avail:{lat2dp}:{lon2dp}:{sortedItems}:{page}`, 60s TTL, evict
  on order, degrade gracefully if Redis down.
- **Geo:** Haversine in-memory scan over ~10K DCs (sub-ms); PostGIS/geohash if larger.
- **Scale:** read replicas for availability, primary for orders, partition by `region_code`.

---

## Cloud Infrastructure & Sizing (AWS ↔ GCP)

> How you'd actually deploy this. Every AWS service below is paired with its GCP equivalent, and every instance count is derived from this design's own back-of-the-envelope numbers.

### Building blocks this design needs
- **Relational DB (primary + read replicas)** — orders and `inventory` live in one Postgres DB so a single local SERIALIZABLE + `FOR UPDATE` transaction gives atomic reserve-and-order; replicas serve the read-heavy availability path.
- **Key-value / cache** — Redis fronts `GET /availability` (`avail:{lat2dp}:{lon2dp}:{items}:{page}`, 60s TTL) to absorb the 175:1 read:write skew and hold the p99 < 100 ms SLA.

(No object store, search cluster, stream/queue, or WebSocket tier: geo lookup is an in-JVM Haversine scan over ~10K DCs, cache eviction runs inline, and driver-tracking/streaming are explicit non-goals.)

### Managed-service equivalents
| Building block | In this repo (dev) | AWS | GCP | When to reach for it |
|---|---|---|---|---|
| Relational DB (primary) | H2 in tests / local Postgres | RDS for PostgreSQL or Aurora PostgreSQL | Cloud SQL for PostgreSQL or AlloyDB for PostgreSQL | Need ACID + SERIALIZABLE + `SELECT ... FOR UPDATE` so two buyers never claim the same unit |
| Relational DB (read replicas) | same H2/Postgres | RDS/Aurora read replicas | Cloud SQL read replicas / AlloyDB read pool | Offload the ~175× availability reads from the write primary |
| Key-value / cache | embedded/local Redis (spring-data-redis) | ElastiCache for Redis (cluster mode) | Memorystore for Redis | Serve most of 20K availability QPS at sub-ms, bound staleness with 60s TTL |

### Compute & capacity sizing (from our BOTE numbers)
Driving numbers (from §2 and DESIGN.md §2):
- **~20K availability QPS** peak (reads); **~115 orders/sec** average, **~1K/sec** peak (writes); **read:write ≈ 175:1**.
- **Cache miss → DB reads ≈ 2K QPS** (derived: assume ~90% Redis hit rate on the 20K read QPS).
- **Storage:** inventory ≈ 60 GB (up to 1B sparse rows); orders ≈ 1 TB/year (partition by `region_code` + archive).

| Tier | Driving number | Sizing logic | AWS | GCP |
|---|---|---|---|---|
| App / API (stateless Spring Boot) | ~21K peak QPS (20K reads + ~1K writes) | ~21K QPS / ~3K QPS per node ≈ 7 nodes + ~50% headroom ≈ **10–12** across 3 AZs | `c7g.2xlarge` (Graviton, compute) ×12 | `c3-standard-8` ×12 |
| Postgres primary (writes) | ~1K write TPS peak; 60 GB + orders growth | One memory-optimized primary (SERIALIZABLE order txns fit easily); scale vertically, then shard by `region_code` | `db.r7g.2xlarge` RDS/Aurora PostgreSQL (bump to `.4xlarge` under contention) | Cloud SQL / AlloyDB `n2-highmem-8` |
| Read replicas (availability) | ~2K read QPS on cache miss | ~2K / ~5K QPS per replica < 1, but run **2–3** for HA + headroom across AZs | 2–3× `db.r7g.2xlarge` read replicas | 2–3× Cloud SQL replicas / AlloyDB read pool (`n2-highmem-8`) |
| Cache (Redis) | ~18K QPS (≈90% of reads); low-GB working set | One shard easily handles 18K GET/s; size for memory + HA (primary+replica), enable cluster mode to grow | ElastiCache `cache.r7g.large` (primary + replica) | Memorystore for Redis, Standard tier ~5–13 GB |

### Load balancing & edge
- **L7 application LB** in front of the stateless app tier — terminates TLS, load-balances HTTP/JSON, can path-split `GET /availability` from `POST /orders`: **AWS ALB**, **GCP external Application Load Balancer**.
- **Geo/latency routing across regional shards** — because inventory/orders shard by `region_code`, route users to their region's stack: **AWS Route 53** latency/geo records → regional ALBs; **GCP Cloud DNS + global external ALB** with regional backends.
- **No CDN** on the API path — both endpoints return dynamic, location-keyed JSON with nothing cacheable at the edge; Redis is the caching layer. (CloudFront / Cloud CDN would only matter if a static web/mobile-asset front-end were added.)
- **No sticky sessions needed** — the app tier is stateless and there is no WebSocket/long-lived-connection tier in scope. (Add sticky/affinity-aware routing only if the real-time-tracking extension introduces WebSockets.)

### VMs vs containers vs serverless — the call
Run the stateless app tier in **containers on managed Kubernetes — AWS EKS (or ECS Fargate) / GCP GKE Autopilot**. Traffic is steady and high-volume rather than bursty-to-zero, and each node keeps warm JVM state plus pooled connections to Postgres and Redis, so serverless (Lambda / Cloud Functions) is a poor fit: cold starts hurt the p99 < 100 ms SLA and per-invocation DB connections would exhaust the primary's pool during the pessimistic-locking order path. Containers give fast HPA autoscaling and rolling deploys without the ops burden of raw VMs, while the databases and cache stay on their managed services above.
