# Bitly URL Shortener — System Design

> **Implementation status:** This repository contains a complete, production-quality Spring Boot
> implementation of the design described here.  Every architectural decision maps directly to
> code in `src/main/java/com/systemdesign/bitly/`.

---

## Table of Contents

1. [Problem Statement & Requirements](#1-problem-statement--requirements)
2. [Capacity Estimation](#2-capacity-estimation)
3. [Core Entities & Data Model](#3-core-entities--data-model)
4. [API Design](#4-api-design)
5. [High-Level Architecture](#5-high-level-architecture)
6. [Deep Dive: Short Code Generation](#6-deep-dive-short-code-generation)
7. [Deep Dive: Caching Strategy](#7-deep-dive-caching-strategy)
8. [Deep Dive: Database Design](#8-deep-dive-database-design)
9. [Deep Dive: Availability](#9-deep-dive-availability)
10. [Deep Dive: Scaling](#10-deep-dive-scaling)
11. [Trade-offs & Alternatives](#11-trade-offs--alternatives)

---

## 1. Problem Statement & Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| FR-1 | `POST /urls` — accepts a long URL and returns a unique shortened URL |
| FR-2 | Optional custom alias (user-specified short code) |
| FR-3 | Optional expiration date; after expiry the link returns HTTP 410 Gone |
| FR-4 | `GET /{shortCode}` — resolves the short code and HTTP 302 redirects to the original URL |

### Non-Functional Requirements

| Metric | Target |
|--------|--------|
| Scale | 1 billion total URLs, 100 million DAU |
| Read/Write ratio | 1,000 : 1 |
| Redirect latency (p99) | < 100 ms |
| Availability | 99.99% (≤ 52 minutes downtime/year) |
| Short code length | 8 characters (URL-safe, base62) |
| Data durability | No URL loss after write acknowledgement |

### Out of Scope (for this reference implementation)

- User authentication and per-user URL management
- Click analytics and geographic tracking
- Rate limiting (belongs at the API Gateway layer)
- Custom domain support (e.g. `acme.co/xyz`)

---

## 2. Capacity Estimation

### Traffic

```
DAU = 100,000,000
Average redirects per user per day = 10
Total redirects/day = 1,000,000,000 (1B)
Read QPS (average)  = 1,000,000,000 / 86,400 ≈ 11,574 RPS
Read QPS (peak 3×)  ≈ 35,000 RPS

Write ratio = 1:1000 → Write QPS (average) ≈ 11.5 WPS
Write QPS (peak)    ≈ 35 WPS  (trivially handled by any modern DB)
```

> **Key insight:** The system is overwhelmingly read-heavy.  Almost all architectural effort goes
> toward serving redirects with sub-100ms latency at 35,000 RPS peak.

### Storage

```
Average URL record size:
  short_code  (8 bytes) + long_url (avg 100 bytes) + metadata (50 bytes) ≈ 160 bytes

1B URLs × 160 bytes = 160 GB  (comfortable on a single PostgreSQL primary with SSDs)
5 year retention = 800 GB  (→ time to add sharding or archival policy)

Redis cache (hot 20% of URLs cover 80% of traffic):
  200M URLs × 160 bytes ≈ 32 GB  (fits in a single Redis instance; trivially shardable)
```

### Bandwidth

```
Inbound  (writes): 35 WPS × 1 KB ≈ 35 KB/s   (negligible)
Outbound (reads):  35,000 RPS × 200 bytes (Location header) ≈ 7 MB/s per node
                   Scaled to 10 nodes ≈ 70 MB/s total  (well within 1 Gbps NIC capacity)
```

---

## 3. Core Entities & Data Model

### Entity: `urls`

```sql
CREATE TABLE urls (
    id              BIGSERIAL       PRIMARY KEY,
    short_code      VARCHAR(100)    NOT NULL UNIQUE,   -- business key
    long_url        TEXT            NOT NULL,
    custom_alias    VARCHAR(100),                       -- null for generated codes
    expiration_date TIMESTAMP,                          -- null = never expires
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);
```

**Design notes:**

- `id` is a surrogate key (BIGSERIAL) used only by JPA internals; all application lookups use `short_code`.
- `long_url` is `TEXT` (no length limit) to accommodate OAuth `redirect_uri` values and marketing URLs that can exceed 2,000 characters.
- `expiration_date` nullable: the vast majority of URLs never expire; a NOT NULL default would waste storage and complicate queries.
- No `updated_at` column: URLs in this system are write-once.  If a URL needed updating the correct approach is to create a new record (preserves audit trail, avoids cache invalidation race conditions).

### JPA Entity (code reference)

See `src/main/java/com/systemdesign/bitly/model/Url.java`.

Key annotations:
```java
@Entity @Table(name = "urls", indexes = { @Index(name = "idx_urls_short_code", ...) })
```
`@PrePersist` sets `createdAt` automatically — no risk of missing it in application code.

---

## 4. API Design

### POST /urls — Create short URL

**Request**
```json
POST /urls
Content-Type: application/json

{
  "long_url":        "https://www.example.com/very/long/path?with=query",
  "custom_alias":    "my-promo",          // optional; 3–100 alphanumeric/-/_ chars
  "expiration_date": "2025-12-31T23:59:59" // optional; ISO-8601
}
```

**Response 201 Created**
```json
{
  "short_url":        "https://bit.ly/0a3Zk8mQ",
  "short_code":       "0a3Zk8mQ",
  "long_url":         "https://www.example.com/very/long/path?with=query",
  "expiration_date":  null,
  "created_at":       "2024-01-15T10:30:00"
}
```

**Error responses** (RFC 7807 Problem Detail format):
- `422 Unprocessable Entity` — validation failure (blank URL, invalid alias chars, etc.)
- `409 Conflict` — custom alias already taken

### GET /{shortCode} — Redirect

**Request**
```
GET /0a3Zk8mQ
```

**Response 302 Found**
```
Location: https://www.example.com/very/long/path?with=query
```

**Error responses:**
- `404 Not Found` — short code does not exist
- `410 Gone` — short code existed but has expired

### Why HTTP 302 (not 301)?

`301 Moved Permanently` is cached indefinitely by browsers, making it impossible to:
1. Update a link's destination
2. Expire a link on schedule
3. Collect server-side analytics (every subsequent click bypasses our servers)

`302 Found` (temporary redirect) causes each click to hit our servers, preserving control,
analytics capability, and the ability to honour expiration dates.

---

## 5. High-Level Architecture

```
                          ┌──────────────────────────────────────────────────┐
                          │                   Clients                         │
                          └───────────────┬──────────────────────────────────┘
                                          │ HTTPS
                                          ▼
                          ┌──────────────────────────────────────────────────┐
                          │            API Gateway / Load Balancer            │
                          │     (rate limiting, TLS termination, routing)     │
                          └────────┬─────────────────────────────────────────┘
                                   │  round-robin
                    ┌──────────────┼──────────────┐
                    ▼              ▼               ▼
              ┌──────────┐  ┌──────────┐   ┌──────────┐
              │ App Node │  │ App Node │   │ App Node │   (Spring Boot instances)
              │  :8080   │  │  :8080   │   │  :8080   │
              └────┬─────┘  └────┬─────┘   └────┬─────┘
                   │              │               │
       ┌───────────┴──────────────┴───────────────┴──────────┐
       │                                                       │
       ▼                                                       ▼
┌──────────────────────┐                        ┌──────────────────────────┐
│      Redis Cluster   │                        │   PostgreSQL Primary     │
│  (LRU cache + counter│                        │   (source of truth)      │
│   url:counter key)   │                        │                          │
│                      │                        │   ┌──────────────────┐   │
│  ┌─────────────────┐ │                        │   │   Read Replica 1 │   │
│  │  Sentinel / HA  │ │                        │   └──────────────────┘   │
│  └─────────────────┘ │                        │   ┌──────────────────┐   │
└──────────────────────┘                        │   │   Read Replica 2 │   │
                                                │   └──────────────────┘   │
                                                └──────────────────────────┘
```

### Write Path (POST /urls)

```
Client
  └─▶ UrlController.createShortUrl()
        └─▶ UrlShorteningService.shorten()
              ├─ (alias?) UrlRepository.existsByShortCode()  [DB write replica or primary]
              ├─ (else)   ShortCodeGenerator.nextCode()       [local AtomicLong, no I/O]
              ├─ UrlRepository.save()                         [INSERT on primary]
              └─ UrlCacheService.evict()                      [Redis DEL]
```

### Read Path (GET /{shortCode})

```
Client
  └─▶ RedirectController.redirect()
        └─▶ UrlRedirectService.resolveUrl()
              ├─ UrlCacheService.get()        [Redis GET  ~1ms]
              │     HIT ──▶ HTTP 302
              │     MISS
              │       └─▶ UrlRepository.findByShortCode()   [PG index scan ~5ms]
              │             NOT FOUND ──▶ HTTP 404
              │             EXPIRED   ──▶ HTTP 410
              │             OK        ──▶ UrlCacheService.put()
              │                           HTTP 302
```

---

## 6. Deep Dive: Short Code Generation

### Options Considered

#### Option A: Hash-based (MD5/SHA-256 + truncation)

```
short_code = base62(MD5(long_url))[0:8]
```

**Problems:**
1. **Collisions require retry loops.** At 1B URLs with 8-char codes (62^8 ≈ 218T possible values), birthday paradox probability of any collision after 1B URLs is about 0.002% — but at scale you need collision detection logic (check DB, retry) in the hot write path.
2. **Idempotency side-effect.** Two users submitting the same long URL get the same short code.  This seems desirable but creates problems: one user can accidentally "delete" another user's link.
3. **Distributed coordination.** Each instance must check the DB for collisions, adding latency.

#### Option B: UUID-based

```
short_code = base62(UUID.randomUUID())[0:8]
```

**Problems:**
1. Same collision concern as hashing, plus UUIDs are random so collisions are possible.
2. UUID truncation loses entropy — the short prefix is not guaranteed unique.

#### Option C: Counter-based with Redis INCRBY batching (CHOSEN)

```
Redis: INCRBY url:counter 1000  → returns N
Instance owns range [N-999, N]
short_code = base62Encode(counter_value)  (left-padded to 8 chars)
```

**Why this wins:**

| Property | Hash | UUID | Counter+Batch |
|----------|------|------|---------------|
| Guaranteed unique | No | No | **Yes** |
| Retry-free | No | No | **Yes** |
| Coordination overhead | 1 DB check/write | 1 DB check/write | **1 Redis call per 1000 writes** |
| Predictable code length | No | No | **Yes (exactly 8 chars)** |
| Human-readable progression | No | No | Yes (monotonic) |

**Capacity check:**
- 62^8 = 218,340,105,584,896 unique codes
- At 1,400 writes/s peak: would take ~4.9 million years to exhaust
- Counter wraps at `Long.MAX_VALUE` = 9.2 × 10^18 — never a practical concern

### Batching design

```java
// Each instance claims 1000 codes atomically:
Long newMax = redisTemplate.opsForValue().increment("url:counter", BATCH_SIZE);
// Owns [newMax - 1000, newMax) exclusively

// Local allocation — zero Redis calls for next 999 codes:
currentCounter.compareAndSet(current, current + 1)
```

**Thread safety:** `AtomicLong` CAS handles intra-instance concurrency.  The `synchronized` block in `claimBatch()` ensures only one thread issues the Redis INCRBY per batch boundary.  The double-checked locking pattern prevents redundant batch claims when multiple threads simultaneously see an exhausted batch.

**Redis failure mode:** If Redis is temporarily unavailable, `claimBatch()` throws `IllegalStateException`.  In production this would be caught at the service boundary and surfaced as HTTP 503.  The batch is never partially consumed — atomicity is guaranteed by the Redis INCRBY operation.

**Multi-instance counter ranges:** Each instance claims disjoint counter ranges, so there is never a conflict even without distributed locking within a batch.

```
Instance A: [0,    1000)  then [3000, 4000) ...
Instance B: [1000, 2000)  then [4000, 5000) ...
Instance C: [2000, 3000)  then [5000, 6000) ...
```

---

## 7. Deep Dive: Caching Strategy

### Why Redis?

1. **Sub-millisecond reads:** Redis GET on a local cluster is ~0.5–1ms, vs ~5ms for a PostgreSQL index scan.  Given the 100ms redirect latency target and the 1000:1 read/write ratio, caching is the primary scaling mechanism.
2. **Native TTL support:** `SET key value EX seconds` sets expiry atomically — no separate scheduled job needed.
3. **LRU eviction:** Redis `maxmemory-policy allkeys-lru` automatically evicts cold entries when memory fills, ensuring hot URLs stay resident without manual management.
4. **String-optimised:** Since both keys and values are plain strings (short codes and URLs), we use `StringRedisTemplate` which avoids Java serialisation overhead and keeps keys human-readable for ops tooling.

### Cache Design

**Key pattern:** `url:{shortCode}` → `longUrl`

```
url:0a3Zk8mQ → https://www.example.com/very/long/path
url:counter   → 847392  (used by ShortCodeGenerator — different namespace purpose)
```

**TTL alignment:**

The Redis TTL is calculated to expire at the same time as (or slightly before) the URL's `expiration_date`:

```java
Duration ttl = expirationDate == null
    ? Duration.ofSeconds(86_400)                         // default: 24h
    : Duration.between(now, expirationDate).clipped to [1s, 86400s];
```

This prevents stale cache hits for expired URLs: when the TTL fires, Redis evicts the entry and the next request falls through to the DB, which returns 410 Gone — then does NOT re-cache the expired record.

**No negative caching:** We deliberately do not cache "not found" results.  Reasons:
1. Miss rate for genuine 404s is negligible compared to total traffic.
2. Negative caching would require invalidation on every new URL creation — complexity for no gain.
3. Caching a 404 for a valid code that was just created would break read-your-writes consistency.

**Cache-aside pattern (explicit, not `@Cacheable`):**

Spring's `@Cacheable` annotation does not support per-entry TTL based on runtime values.  We use an explicit get/put/evict pattern in `UrlCacheService` to set TTLs that vary per URL.

**Cache warm-up on miss:**

When a redirect triggers a DB lookup (cache miss), the result is immediately cached before returning the response.  The very next request for the same code hits Redis.  At 1000:1 read/write ratio, even if the first request after creation is a cache miss, the remaining 999 requests are cache hits.

**Redis degradation:** All Redis calls are wrapped in try/catch.  A Redis failure degrades gracefully to DB-only mode.  This trades latency for availability — the correct trade-off for a 99.99% SLA.

---

## 8. Deep Dive: Database Design

### Why PostgreSQL?

1. **ACID guarantees:** The `short_code` UNIQUE constraint prevents duplicate codes at the DB level, giving a safety net even if the application layer has a bug.
2. **TEXT column for long URLs:** PostgreSQL's `TEXT` type has no practical length limit, unlike MySQL's VARCHAR(65535) which can still be insufficient for certain OAuth URLs.
3. **BIGSERIAL:** Native auto-increment for the surrogate `id`, aligned with our counter-based code generation (though the application counter is in Redis, not the DB sequence).
4. **Partial indexes:** PostgreSQL supports `WHERE expiration_date IS NOT NULL` partial indexes, which are significantly smaller and faster for queries that only care about expiring URLs.

### Index Strategy

```sql
-- Primary lookup (every redirect): B-tree, O(log n)
CREATE UNIQUE INDEX idx_urls_short_code ON urls (short_code);

-- Cleanup job (find expired records): partial index
CREATE INDEX idx_urls_expiration_date ON urls (expiration_date)
    WHERE expiration_date IS NOT NULL;

-- Monitoring (recent creation count): B-tree DESC
CREATE INDEX idx_urls_created_at ON urls (created_at DESC);
```

**Why not index `long_url`?** Deduplication by long URL would require a full-text index on a `TEXT` column.  PostgreSQL B-tree indexes on `TEXT` are limited to 2704 bytes (index tuple size limit).  Long URLs frequently exceed this.  The performance cost and complexity outweighs the benefit — we choose not to deduplicate.

### Connection Pool Tuning

HikariCP pool size = 20 connections.  Based on the formula:
```
pool_size = (num_cores × 2) + effective_spindle_count
          = (8 × 2) + 4 = 20  (for a typical 8-core app server with NVMe SSDs)
```

With 10 app nodes and 20 connections each = 200 total connections to PostgreSQL.  PostgreSQL defaults support 100; production deployments should use **PgBouncer** in transaction-pooling mode to reduce actual server connections to ~20-50.

### Write Batching

JPA batch insert is configured:
```yaml
spring.jpa.properties.hibernate.jdbc.batch_size: 50
spring.jpa.properties.hibernate.jdbc.order_inserts: true
```

At peak 35 WPS this is not needed, but the configuration is correct for burst scenarios and bulk import tooling.

---

## 9. Deep Dive: Availability

### 99.99% Availability Target

99.99% = 52 minutes downtime per year.  This requires eliminating all single points of failure.

### Component-by-component HA

#### Application Tier

- **Multiple instances** behind a load balancer.
- **Stateless by design:** all state lives in Redis or PostgreSQL.  Any instance can handle any request.
- **Health checks:** Spring Actuator `/actuator/health` exposes Redis + DB health.  The load balancer removes unhealthy instances within seconds.

#### Redis HA

**Redis Sentinel** (3+ sentinels) for automatic primary failover:

```
           ┌─────────────┐
           │ Redis Primary│  ◄── writes (url:counter INCRBY, url:* SET)
           └──────┬───────┘
                  │ replication
        ┌─────────┴─────────┐
        ▼                   ▼
  ┌──────────┐       ┌──────────┐
  │ Replica 1│       │ Replica 2│  ◄── reads (url:* GET) can be served here
  └──────────┘       └──────────┘
        │                   │
        └─────────┬─────────┘
                  ▼
         ┌───────────────┐
         │ 3× Sentinels  │  (monitor + auto-failover)
         └───────────────┘
```

**Failover window:** ~15–30 seconds (Sentinel `down-after-milliseconds` + election).  During this window, `ShortCodeGenerator.claimBatch()` will throw, failing writes.  Reads degrade to PostgreSQL-only with elevated latency but remain functional.

**Alternative: Redis Cluster** (6+ nodes) for horizontal scale.  Preferred when URL count pushes Redis memory >32GB.

#### PostgreSQL HA

- **Streaming replication** to 2+ read replicas.
- **Automatic failover** via Patroni + etcd (production) or managed service (AWS RDS Multi-AZ).
- **Reads on replicas:** redirect lookups (cache misses) can use a read replica connection pool.  Acceptable replication lag for our use case is <1s.

**Multi-region counter ranges:** When deploying across multiple regions to achieve <100ms global latency, each region's counter generator claims a different high-order prefix:

```
Region US-EAST: counters 0     – 10^12
Region EU-WEST: counters 10^12 – 2×10^12
Region AP-EAST: counters 2×10^12 – 3×10^12
```

This eliminates cross-region Redis coordination for the common case.  Overflow (regional traffic exceeding the allocated range) is handled by claiming from a global overflow pool.

---

## 10. Deep Dive: Scaling

### Read Scaling (the critical path)

At 35,000 RPS peak with a Redis cache hit rate of ~99%:

```
Requests hitting Redis  = 34,650 RPS  (handled by a 3-node Redis cluster comfortably)
Requests hitting PG     = 350 RPS     (handled by 2 read replicas)
```

**Horizontal scaling for reads:**
1. Add more application instances (stateless → trivial).
2. Scale Redis: add shards in Redis Cluster mode.
3. Scale PostgreSQL: add read replicas.  Each replica can handle ~5,000 simple index-scan QPS.

**CDN for predictable hot links:** Marketing campaign URLs (e.g. Super Bowl ads) can cause 100×-1000× traffic spikes.  A CDN edge node (Cloudflare, Fastly) can cache the `302 Location` response for popular short codes with a short TTL (e.g. 30s).  This pushes serving entirely to the CDN edge for hot links, eliminating the need to over-provision application nodes.

```
CDN cache hit rate for top-1000 links ≈ 90% of all traffic (Zipf distribution)
Remaining 10% served by application nodes with Redis
```

### Write Scaling

At 35 WPS peak, the write path is not a bottleneck.  PostgreSQL primary handles >10,000 simple INSERTs/s on modern hardware.

**If write scale becomes a concern (10,000+ WPS):**
1. **Async write buffering:** Accept the URL in memory (Redis list), return the short code immediately, and flush to PostgreSQL asynchronously via a background worker.  Risk: data loss window if app crashes before flush.
2. **Write sharding:** Shard PostgreSQL by short code prefix (first character → 62 shards).  Consistent hashing routes each write to the correct shard.

### Database Scaling

**Vertical scaling first:** A PostgreSQL instance on 32 cores + 256GB RAM + NVMe SSDs can handle the full 1B URL dataset comfortably for several years.

**When to shard:**
- Dataset exceeds single-node storage (>8TB practical limit for operational simplicity)
- Write QPS exceeds single-node write throughput (~10,000 WPS)

**Sharding strategy (if needed):** Shard by first character of `short_code` → 62 shards.  Each shard handles ~16M URLs at 1B total.  The `ShortCodeGenerator` must be shard-aware (counter ranges partition across shards).

---

## 11. Trade-offs & Alternatives

### Decision 1: No URL deduplication

**What we do:** Two requests with the same `long_url` receive different `short_code`s.

**Alternative:** Index `long_url` and return existing short code if URL was already shortened.

**Why we didn't:** A B-tree index on `TEXT` has a PostgreSQL size limit of ~2704 bytes; many URLs exceed this.  A hash index (`MD5(long_url)`) would work but adds complexity.  The benefit (slightly fewer records) doesn't justify the complexity at our scale.

### Decision 2: HTTP 302 (not 301)

**What we do:** Always return 302 for redirects.

**Alternative:** Return 301 to let browsers cache the redirect and skip future server round-trips.

**Trade-off:** 301 reduces our server load for repeat visitors but permanently removes server control.  Updating or expiring a 301-cached link requires asking every user to clear their browser cache — infeasible at scale.

### Decision 3: Redis counter over database sequence

**What we do:** Redis INCRBY for counter generation, PostgreSQL BIGSERIAL only as surrogate PK.

**Alternative:** Use PostgreSQL sequence directly for short code generation.

**Why Redis wins:** PostgreSQL sequences require a round-trip to the database primary for every code generation.  At peak write load this adds ~5ms latency per write and creates load on the primary.  Redis INCRBY is ~0.3ms and the batching reduces it to 0.0003ms amortised per code.

### Decision 4: Cache-aside over write-through

**What we do:** Write to PostgreSQL first, then evict from Redis.  Cache warms on first read miss.

**Alternative (write-through):** Write to Redis and PostgreSQL simultaneously on every create.

**Why cache-aside wins:** Write-through would cache every URL regardless of whether it's ever read.  Given the Zipf distribution of URL popularity (most URLs are never clicked again after creation), this would waste Redis memory on cold data.  Cache-aside naturally focuses Redis memory on hot URLs.

### Decision 5: No idempotency key

**What we do:** Each POST creates a new short code even for duplicate `long_url`s.

**Alternative:** Accept an `idempotency_key` header; return the same short code for duplicate requests.

**Why we didn't (yet):** Idempotency requires storing request hashes and introduces complexity in the write path.  For most URL shortener use cases, the client stores the returned `short_url` and never re-submits the same request.

### Decision 6: `schema.sql` over Flyway/Liquibase

**What we do:** Spring runs `schema.sql` (idempotent DDL) on startup.

**Alternative:** Use Flyway or Liquibase for version-controlled migrations.

**Why schema.sql for this reference:** Simplifies setup with no additional dependencies.  **In production, use Flyway** (`spring.flyway.enabled=true`) for:
- Tracked migration history
- Rollback scripts
- Multi-environment schema promotion (dev → staging → prod)
- Zero-downtime migrations (add column, then backfill, then add NOT NULL constraint)

---

## Running Locally

### Prerequisites

```bash
# Start PostgreSQL
docker run -d --name bitly-pg \
  -e POSTGRES_DB=bitly \
  -e POSTGRES_USER=bitly \
  -e POSTGRES_PASSWORD=bitly \
  -p 5432:5432 postgres:15

# Start Redis
docker run -d --name bitly-redis \
  -p 6379:6379 redis:7 redis-server --loglevel warning
```

### Build & Run

```bash
cd bitly
./mvnw spring-boot:run
```

### Try It

```bash
# Shorten a URL
curl -X POST http://localhost:8080/urls \
  -H "Content-Type: application/json" \
  -d '{"long_url": "https://www.example.com/very/long/path"}'

# Response:
# {"short_url":"http://localhost:8080/00000000","short_code":"00000000",...}

# Follow the redirect
curl -v http://localhost:8080/00000000
# < HTTP/1.1 302 Found
# < Location: https://www.example.com/very/long/path

# Custom alias with expiration
curl -X POST http://localhost:8080/urls \
  -H "Content-Type: application/json" \
  -d '{
    "long_url": "https://example.com",
    "custom_alias": "my-link",
    "expiration_date": "2025-12-31T23:59:59"
  }'
```

### Swagger UI

Open [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) to explore and test the API interactively.

### Metrics

Prometheus metrics exposed at [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus).

Key metrics to monitor:
- `http_server_requests_seconds` — redirect latency histogram
- `hikaricp_connections_active` — DB connection pool saturation
- `redis_commands_duration_seconds` — Redis latency
- `jvm_memory_used_bytes` — heap usage per instance
