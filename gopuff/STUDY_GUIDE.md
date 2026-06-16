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
