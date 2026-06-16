# GoPuff-like Local Delivery Service — System Design

A reference implementation of an on-demand local delivery service: customers check what
can be delivered to their location within ~1 hour, then place an atomic multi-item order.
Inventory lives in geographically distributed micro-fulfillment centers ("distribution
centers" / DCs).

---

## 1. Problem Statement & Requirements

### Functional
- **`GET /availability?latitude=X&longitude=Y&items=A,B,C&page=N`** — given a user's
  coordinates and a set of catalog item IDs, return which items can be delivered (i.e. are
  in stock at a DC within delivery range), aggregated across nearby DCs, paginated.
- **`POST /orders`** — purchase multiple items atomically. If *any* item cannot be
  fulfilled, reject the *entire* order. No partial fulfillment.

### Non-Functional
- **Availability latency:** p99 < 100 ms. Served from a Redis cache (1-minute TTL).
- **Order consistency:** strong. Two customers must never be able to buy the same physical
  unit. Backed by a SERIALIZABLE Postgres transaction + `SELECT ... FOR UPDATE`.
- **Scale:** 10K DCs, 100K catalog items, 10M orders/day (~115 orders/sec average).
- **Availability QPS:** ~20K (10M orders × ~10 searches per buyer ÷ ~5% conversion).

### Explicitly out of scope
Driver assignment, routing/ETA, payments, returns/cancellation refunds, real-time tracking.
These are discussed as extensions in `STUDY_GUIDE.md`.

---

## 2. Capacity Estimation

| Quantity | Estimate | Notes |
|---|---|---|
| DCs | 10K | Geo-located hubs |
| Catalog items | 100K | SKUs |
| Inventory rows | up to 10K × 100K = **1B** | Realistically sparse (each DC stocks a subset), so far fewer in practice |
| Orders/day | 10M | → **~115 orders/sec** average, plan ~5–10× peak (~1K/sec) |
| Availability QPS | **~20K** | Read-heavy: ~175:1 read:write ratio |
| Availability SLA | **p99 < 100 ms** | Cache-first |

**Storage rough cut:**
- Inventory row ≈ 60 B → even a full 1B rows ≈ 60 GB (fits one well-provisioned Postgres
  primary; partition by region as it grows).
- Orders: 10M/day × ~300 B ≈ 3 GB/day ≈ ~1 TB/year → time/region partition + archival.

**Read vs write split:** availability is ~175× the order rate. This justifies (a) a Redis
cache in front of availability, and (b) routing availability reads to **read replicas**
while orders go to the **primary**.

---

## 3. Core Entities & Data Model

```
items                 (catalog SKUs)
distribution_centers  (geo-located hubs)
inventory             (item × dc → quantity, reserved_quantity)   <- the contended table
orders                (order header, fulfilling_dc_id, status)
order_items           (order line items, price snapshot)
```

### The key design choice: `quantity` vs `reserved_quantity`

`inventory` stores two counters per (item, DC):

- `quantity` — physical units on hand.
- `reserved_quantity` — units committed to orders that are accepted but not yet shipped.

The amount a new buyer can claim is the **computed** `available = quantity - reserved_quantity`.

Why model reservations instead of just decrementing `quantity`?
- **Reversibility:** cancelling an order un-reserves without losing the physical count.
- **Auditability:** "what's physically here" and "what's promised" are separate facts.
- **Safety:** the DB enforces `0 <= reserved_quantity <= quantity` via CHECK constraints,
  so even a buggy service can never oversell at the storage layer.

Full DDL is in [`src/main/resources/schema.sql`](src/main/resources/schema.sql).

---

## 4. API Design

### `GET /availability`
**Query params:** `latitude` (double), `longitude` (double), `items` (CSV of itemIds),
`page` (int, default 0, page size 20).

**200 OK**
```json
{
  "latitude": 40.7128,
  "longitude": -74.006,
  "page": 0,
  "pageSize": 20,
  "totalItems": 2,
  "items": [
    { "itemId": "item-a", "itemName": "Apple", "available": true,  "quantity": 10, "dcId": "dc-2" },
    { "itemId": "item-b", "itemName": "Bread", "available": false, "quantity": 0,  "dcId": null  }
  ]
}
```
- `quantity` = total available across nearby DCs. `dcId` = DC with the most stock (preferred
  fulfiller), or null if unavailable.

**422 Unprocessable Entity** — no DC serves this location (`NO_DC_FOUND`).

### `POST /orders`
**Body**
```json
{
  "userId": "user-1",
  "latitude": 40.7128,
  "longitude": -74.006,
  "items": [ { "itemId": "item-a", "quantity": 2 }, { "itemId": "item-b", "quantity": 1 } ]
}
```

**201 Created**
```json
{
  "orderId": "f3c9...-uuid",
  "status": "CONFIRMED",
  "fulfillingDcId": "dc-1",
  "confirmedItems": [ { "itemId": "item-a", "quantity": 2 }, { "itemId": "item-b", "quantity": 1 } ]
}
```

| Status | Code | When |
|---|---|---|
| 201 | — | Order confirmed |
| 409 | `ITEM_UNAVAILABLE` | Any item out of stock / no single DC can fulfill the whole order |
| 422 | `NO_DC_FOUND` | No DC serves the location |
| 400 | `VALIDATION_ERROR` | Bad request body |

Error envelope is uniform: `{ timestamp, status, error, message }` (see
`GlobalExceptionHandler`).

---

## 5. High-Level Architecture

```
                       ┌──────────────────────────── READ PATH (≈20K QPS) ───────────────────────────┐
                       │                                                                              │
  Client ── GET /availability ──> AvailabilityController ──> AvailabilityService                      │
                                                                  │                                   │
                                                       1. Redis GET (avail:{key})  ── HIT ──> return  │
                                                                  │ MISS                              │
                                                       2. NearbyDcService (in-mem Haversine scan)     │
                                                       3. InventoryRepository (read replica)          │
                                                       4. aggregate per item across DCs               │
                                                       5. Redis SET (TTL 60s) ── return               │
                       └──────────────────────────────────────────────────────────────────────────┘

                       ┌──────────────────────────── WRITE PATH (≈115 OPS) ──────────────────────────┐
                       │                                                                              │
  Client ── POST /orders ──> OrderController ──> OrderService.placeOrder()                            │
                                                     @Transactional(SERIALIZABLE)  [Postgres PRIMARY] │
                                                     1. NearbyDcService                               │
                                                     2. for nearest capable DC:                       │
                                                          SELECT ... FOR UPDATE each inventory row    │
                                                          re-check available >= qty                   │
                                                          reserved_quantity += qty                    │
                                                     3. INSERT order + order_items                    │
                                                     4. Redis evict availability                      │
                       └──────────────────────────────────────────────────────────────────────────┘
```

Orders and inventory live in the **same** Postgres database, so a single local transaction
gives us atomicity — no distributed transaction / 2PC needed (see §6).

---

## 6. Deep Dive: Order Consistency (the heart of the design)

**Requirement:** two customers must never reserve the same physical unit. The last can of
soda goes to exactly one of them.

### Why SERIALIZABLE + `SELECT ... FOR UPDATE` together (belt-and-suspenders)

`OrderService.placeOrder()` runs in `@Transactional(isolation = SERIALIZABLE)` and, for each
line item, calls `findByItemIdAndDcIdWithLock(...)` which is annotated
`@Lock(PESSIMISTIC_WRITE)` → Hibernate emits `SELECT ... FOR UPDATE`.

- **Pessimistic row lock (`FOR UPDATE`)** — the inventory row is locked the instant we read
  it and held until commit. A concurrent order for the same row *blocks* at its own
  `SELECT ... FOR UPDATE` until we commit, then reads our updated `reserved_quantity` and
  correctly sees less availability. This alone prevents the classic lost-update race.
- **SERIALIZABLE isolation** — guards the *aggregate* reasoning, not just single rows. The
  decision "this DC can fulfill the whole order" reads multiple rows; SERIALIZABLE ensures
  the transaction behaves as if it ran with no concurrent interleaving, catching phantom-ish
  anomalies the row lock might miss. Postgres uses SSI (Serializable Snapshot Isolation); on
  a true conflict it aborts one transaction with a serialization failure, which the client
  retries.

Using both is deliberate: the row lock serializes the common contended case cheaply and
deterministically; SERIALIZABLE is the correctness backstop.

### Optimistic vs pessimistic locking — why pessimistic here

| | Optimistic (version column, retry on conflict) | Pessimistic (`SELECT ... FOR UPDATE`) |
|---|---|---|
| Best when | Low contention | **High contention on hot rows** |
| Hot-item behavior | Many retries → wasted work, tail-latency spikes | One waiter proceeds, others queue briefly |
| Complexity | Retry loop in app | Lock handled by DB |

Popular items are *hot rows* (everyone wants the trending snack). Under optimistic locking,
N concurrent buyers cause N−1 retries — thrashing. Pessimistic locking serializes them with
a short wait. For a checkout path where we want a deterministic "you got it / you didn't,"
pessimistic is the better fit. (Optimistic remains a valid alternative for low-contention
inventory — discussed in §11.)

### Why no distributed transaction

Inventory reservation and order creation are **co-located in one Postgres database**. One
local ACID transaction covers both: reserve rows → insert order → commit, atomically. If we
had split inventory and orders into separate databases/services, we'd need a saga or 2PC —
more moving parts, more failure modes, weaker guarantees. Keeping them together is the
single biggest simplifier in this design. (Cross-DB only becomes necessary at extreme scale,
addressed by regional partitioning in §9, where each region is still a single transactional
unit.)

### All-or-nothing within one transaction

`reserveAtFirstCapableDc` reserves every line item or none. If any item is short, the method
returns without committing reservations for that DC and we move to the next DC; if no DC can
fulfill the whole order, we throw `ItemUnavailableException` and the transaction rolls back —
so even partial reservations made while probing are undone.

---

## 7. Deep Dive: Availability & Geo Lookup

### Finding nearby DCs (Haversine)
`NearbyDcService` computes the great-circle distance between the user and every active DC via
the **Haversine formula** (pure Java, no PostGIS dependency) and keeps DCs within
`app.dc.max-distance-miles` (default 60 mi ≈ 1-hour driving proxy), sorted nearest-first.

With only ~10K DCs, an in-memory linear scan is sub-millisecond. The active-DC list is loaded
once at startup (`@PostConstruct`) into an `AtomicReference` snapshot; in production it would
refresh periodically (DC topology changes slowly). For 100K+ DCs you'd add a spatial index
(geohash / R-tree / PostGIS) or pre-filter by `region_code` (zipcode prefix) before the
distance check.

### Cache key design (rounded coordinates)
Key: `avail:{lat2dp}:{lon2dp}:{sorted,distinct itemIds}:{page}`.

- Coordinates rounded to **2 decimal places (~1.1 km)** so neighbors share cache entries,
  dramatically raising hit rate. Rounding to ~1 km rarely changes which DCs are in range
  (the radius is 60 mi), so it's a safe approximation.
- Item IDs are **sorted + deduped** so request ordering doesn't fragment the cache.

### TTL tradeoff
60-second TTL bounds staleness: a buyer might see an item as available up to ~1 min after it
sold out elsewhere — acceptable because the *order* path re-validates under lock and will
cleanly reject if it's actually gone. We also proactively evict on order (§10). Shorter TTL =
fresher but more DB load; 60s balances the 100 ms SLA against the write rate.

---

## 8. Deep Dive: Inventory Model

- **`available = quantity - reserved_quantity`** (computed; never stored, so it can't drift).
- **Atomic decrement = reserve under lock:** within the SERIALIZABLE txn we lock the row,
  re-check `available >= requested`, then `reserved_quantity += requested`. DB CHECK
  constraints (`reserved_quantity <= quantity`, both `>= 0`) are a hard backstop.
- **Why reject the whole order instead of partial fulfillment:** a local delivery is one
  trip from one DC. Partially fulfilling ("we'll bring 2 of 3 items") creates a poor UX,
  complicates pricing/refunds, and risks splitting a single delivery across DCs. The product
  requirement is all-or-nothing, so we enforce it: either one nearby DC has everything, or we
  return 409. (Split fulfillment is a deliberate non-goal; see §11.)

---

## 9. Deep Dive: Scaling

- **Reads → replicas:** availability is ~175× orders. Route `GET /availability` to Postgres
  **read replicas**; Redis absorbs the bulk anyway. Replica lag is tolerable because the
  order path re-validates with strong consistency.
- **Writes → primary:** orders hit the **primary** for SERIALIZABLE correctness.
- **Regional partitioning by zipcode prefix (`region_code`):** shard DCs/inventory/orders by
  region. A user in region R only ever touches region R's DCs, so each shard stays a single
  transactional unit and the global write rate fans out across regions. Hot regions scale
  independently.
- **Redis:** cluster mode, key-sharded by the `avail:` key. Cache invalidation on order
  (§10) keeps availability roughly fresh; the 60s TTL is the safety net.
- **Inventory table growth:** partition `inventory` by `region_code` / DC range; the unique
  `(item_id, dc_id)` constraint and per-column indexes keep point lookups O(1)-ish.

---

## 10. Deep Dive: Availability Caching

- **Cache key:** see §7 (rounded coords + sorted items + page).
- **TTL:** 60s, bounding staleness (§7).
- **Eviction on inventory change:** after a successful order, `OrderService` calls
  `cacheService.evictByDcId(dcId)` so subsequent availability reads recompute. The reference
  implementation uses a non-blocking Redis `SCAN` over `avail:*` (cache keys are
  coordinate-based, not DC-based, so we can't target precisely without a secondary index). A
  production system would maintain a **tag set** per DC (`dc:{dcId}:keys`) and delete exactly
  the tagged keys, or simply lean on the short TTL.
- **Graceful degradation:** every Redis call in `AvailabilityCacheService` is wrapped in
  try/catch. If Redis is down: reads return empty (→ treated as a miss → fall through to the
  DB), writes/evicts are no-ops. The service stays **available**, just slower — it loses the
  cache's latency/QPS benefit but never the correctness of the DB.

---

## 11. Trade-offs & Alternatives

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Order isolation | SERIALIZABLE + `FOR UPDATE` | READ COMMITTED + optimistic version | Hot-item contention → pessimistic avoids retry storms; SERIALIZABLE is the correctness backstop |
| Fulfillment | Single DC, all-or-nothing | Split across multiple DCs | Single delivery trip; simpler pricing/UX; matches requirement |
| Availability freshness | Cached, eventually consistent (60s) | Strongly consistent reads | 100 ms SLA at 20K QPS needs cache; order path re-validates strongly |
| Inventory accounting | `quantity` + `reserved_quantity` | Single decrementing counter | Reversible (cancellations), auditable, DB-enforced safety |
| Orders + inventory storage | One Postgres DB (local txn) | Separate services + saga/2PC | Local ACID transaction is far simpler and stronger |
| Geo lookup | In-memory Haversine scan | PostGIS / geohash index | ~10K DCs → linear scan is sub-ms; index needed only at far larger DC counts |
| Cache eviction | `SCAN` + TTL (reference) | Tag-based targeted eviction | Reference simplicity; tagging is the production upgrade |

**The one-liner:** keep orders and inventory in a single Postgres database and serialize the
contended inventory rows with `SELECT ... FOR UPDATE` inside a SERIALIZABLE transaction;
serve the 175×-heavier availability reads from a short-TTL Redis cache that degrades
gracefully and is invalidated on write.
