# Code Walkthrough

A guided tour of the codebase. Pair this with `DESIGN.md` (the why) and `STUDY_GUIDE.md`
(interview framing).

---

## Suggested reading order
1. `model/Inventory.java` — the contended entity; understand `quantity` vs `reservedQuantity`
   and the computed `getAvailableQuantity()`.
2. `repository/InventoryRepository.java` — note `findByItemIdAndDcIdWithLock` (`FOR UPDATE`).
3. `service/OrderService.java` — the consistency-critical write path.
4. `service/NearbyDcService.java` — Haversine geo lookup.
5. `service/AvailabilityService.java` — cache-first read path + aggregation.
6. `cache/AvailabilityCacheService.java` — Redis get/put/evict + graceful degradation.
7. `controller/*` and `exception/GlobalExceptionHandler.java` — HTTP surface + error mapping.
8. `resources/schema.sql`, `resources/application.yml` — storage + config.
9. `src/test/...` — executable specification of the behaviors above.

---

## Package map
```
com.systemdesign.gopuff
├── GopuffApplication            entry point
├── controller/                  HTTP endpoints (thin)
│   ├── AvailabilityController   GET /availability
│   └── OrderController          POST /orders
├── service/                     business logic
│   ├── AvailabilityService      read path: cache -> geo -> inventory -> aggregate -> cache
│   ├── NearbyDcService          Haversine DC discovery (in-memory snapshot)
│   └── OrderService             write path: SERIALIZABLE txn + FOR UPDATE reservation
├── cache/
│   └── AvailabilityCacheService Redis JSON cache (get/put/evict, graceful degradation)
├── model/                       JPA entities + DTOs + OrderStatus enum
├── repository/                  Spring Data JPA repositories
├── exception/                   domain exceptions + GlobalExceptionHandler
└── config/                      AppProperties, RedisConfig
```

---

## Inventory model explained
`Inventory` holds two integers per (item, DC):
- `quantity` — physical units on hand.
- `reservedQuantity` — units committed to accepted-but-unshipped orders.

`getAvailableQuantity()` returns `quantity - reservedQuantity` and is `@Transient` (never
persisted) so it can't drift. The database enforces the invariant with CHECK constraints in
`schema.sql`: `quantity >= 0`, `reserved_quantity >= 0`, `reserved_quantity <= quantity`.

**Key invariant:** `0 <= reserved_quantity <= quantity` at all times; `available = quantity -
reserved_quantity`. Placing an order *increases* `reserved_quantity`; cancelling would
decrease it; shipping would decrease both `quantity` and `reserved_quantity`.

---

## Availability flow, traced step by step
`GET /availability` → `AvailabilityController.getAvailability(...)` builds an
`AvailabilityRequest` → `AvailabilityService.getAvailability(req)`:

1. **`buildCacheKey(req)`** → `{lat2dp}:{lon2dp}:{sorted,distinct items}:{page}` (rounded
   coordinates raise hit rate; sorted items normalize ordering).
2. **`cacheService.get(key)`** — Redis GET. On **HIT**, return immediately (the ~20K-QPS happy
   path; no DB touched).
3. On **MISS**, **`nearbyDcService.findNearbyDcs(lat, lon)`** — Haversine scan; empty →
   `NoDcFoundException` (HTTP 422).
4. **`inventoryRepository.findByDcIdInAndItemIdIn(dcIds, itemIds)`** — one bulk query (no N+1).
5. **`itemRepository.findByItemIdIn(itemIds)`** — one bulk query for item names.
6. **Aggregate** per item across DCs: sum `availableQuantity`, track the DC with the most
   stock as the preferred `dcId`. Items with 0 → `available=false, dcId=null`.
7. **Paginate** (page size 20).
8. **`cacheService.put(key, response)`** — Redis SET with 60s TTL.
9. Return `AvailabilityResponse`.

---

## Order flow, traced step by step
`POST /orders` → `OrderController.placeOrder(@Valid req)` → `OrderService.placeOrder(req)`,
annotated **`@Transactional(isolation = SERIALIZABLE)`**:

1. **`nearbyDcService.findNearbyDcs(lat, lon)`** — empty → `NoDcFoundException` (422).
2. **`reserveAtFirstCapableDc(nearbyDcs, items)`** — walk DCs nearest-first. For each DC:
   - For every line item, **`inventoryRepository.findByItemIdAndDcIdWithLock(itemId, dcId)`**
     → emits `SELECT ... FOR UPDATE`, locking the row.
   - Re-check `availableQuantity >= requested`. If any line is short, abandon this DC and try
     the next.
   - If all lines fit, **reserve**: `reservedQuantity += requested` and `save`.
   - Return the DC id. If no DC can fulfill the whole order → return `null`.
3. `null` → **`ItemUnavailableException`** (HTTP 409); transaction rolls back, so any probing
   reservations are undone.
4. **Persist** the `Order` (status `CONFIRMED`, `fulfillingDcId`) and `OrderItem`s (price
   snapshotted from the catalog).
5. **`cacheService.evictByDcId(fulfillingDcId)`** — invalidate availability after the stock
   change.
6. Return `PlaceOrderResponse` (orderId, CONFIRMED, fulfillingDcId, confirmedItems). HTTP 201.

---

## Why SERIALIZABLE + pessimistic lock together (belt-and-suspenders)
- **`FOR UPDATE` (pessimistic):** locks each inventory row on read; a concurrent order for the
  same row blocks until we commit, then sees our updated `reservedQuantity`. Kills the
  lost-update race deterministically — ideal for hot popular items where optimistic retry
  would thrash.
- **SERIALIZABLE:** correctness backstop for the multi-row "can this DC fulfill the whole
  order" decision; Postgres SSI aborts truly conflicting transactions (client retries).
- Together: cheap deterministic behavior on the contended path **plus** a formal-correctness
  guarantee for the aggregate reasoning. See `DESIGN.md` §6.

---

## Cache key design (rounded coordinates)
`AvailabilityService.buildCacheKey` formats lat/lon with `"%.2f"` (~1.1 km buckets) so nearby
users share entries, and joins a `TreeSet` of item IDs so `items=a,b` and `items=b,a` hit the
same key. Verified by `AvailabilityServiceTest.buildCacheKey_roundsCoordinates_andSortsItems`.

---

## Error handling flow
`GlobalExceptionHandler` (a `@RestControllerAdvice`) maps:
- `ItemUnavailableException` → **409** `ITEM_UNAVAILABLE`
- `NoDcFoundException` → **422** `NO_DC_FOUND`
- `MethodArgumentNotValidException` → **400** `VALIDATION_ERROR`
- `IllegalArgumentException` → **400** `BAD_REQUEST`
- anything else → **500** `INTERNAL_ERROR`

All responses share the envelope `{timestamp, status, error, message}`.

---

## Test structure
- **Unit (Mockito, `@ExtendWith(MockitoExtension.class)`):**
  - `NearbyDcServiceTest` — Haversine correctness (NYC↔LA ≈ 2451 mi, 1° lat ≈ 69 mi, same
    point = 0) and radius filtering + nearest-first sort.
  - `AvailabilityServiceTest` — cache hit (no DB), cache miss → DB query + cross-DC
    aggregation + cache write, no-DC → 422, cache-key formatting.
  - `OrderServiceTest` — success (reserve + save + evict), item OOS → 409 with nothing
    committed, no DC → 422, fall-through to the next capable DC.
- **Web slice (`@WebMvcTest` + `@MockBean`):**
  - `AvailabilityControllerTest` — 200 with body, 422 mapping.
  - `OrderControllerTest` — 201 success, 409 mapping, 400 validation.

No real Postgres/Redis is needed; `src/test/resources/application.yml` excludes Redis
auto-config and disables SQL init. Run `mvn clean test` — 18 tests, all green.

---

## Dependency flow diagram
```
AvailabilityController ─> AvailabilityService ─┬─> NearbyDcService ─> DistributionCenterRepository
                                               ├─> InventoryRepository
                                               ├─> ItemRepository
                                               └─> AvailabilityCacheService ─> Redis

OrderController ─────────> OrderService ───────┬─> NearbyDcService
                                               ├─> InventoryRepository (FOR UPDATE)
                                               ├─> ItemRepository
                                               ├─> OrderRepository
                                               ├─> OrderItemRepository
                                               └─> AvailabilityCacheService (evict)
```

---

## Key invariants (memorize)
- `0 <= reserved_quantity <= quantity` — always (DB CHECK constraints).
- `available = quantity - reserved_quantity` — computed, never stored.
- An order is **all-or-nothing** and **single-DC**: fully reserved at one nearest capable DC,
  or rolled back with a 409.
- Availability may be **stale up to the 60s TTL**, but the order path **re-validates under
  lock**, so staleness can never cause a double-sell — only a clean rejection.
