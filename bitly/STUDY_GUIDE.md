# Bitly URL Shortener — Interview Study Guide

> Use this guide to prep for system design interviews. It is structured around how interviewers actually probe this problem: clarifying questions first, then core decisions, then follow-ups.

---

## 1. Start Here — Clarifying Questions to Ask the Interviewer

Always ask these before drawing anything. They shape almost every decision.

| Question | Why it matters |
|----------|----------------|
| Read/write ratio? | Determines whether to optimise for write throughput or read latency |
| Expected scale? (URLs, DAU) | Drives storage sizing, cache sizing, sharding decisions |
| Do we need analytics? (click counts, geo) | Adds a whole write-heavy analytics pipeline |
| Do we need user auth? | Adds auth service, per-user URL management |
| Custom aliases? | Changes short code generation — must handle conflicts |
| URL expiration? | Adds TTL management, 410 Gone response, cache eviction alignment |
| Global or single-region? | Multi-region adds counter range partitioning complexity |
| Latency target? | Determines whether a CDN is required |

**Answers assumed in this design:**
- 1B URLs, 100M DAU, 1000:1 read:write
- No auth, no analytics (out of scope)
- Yes to custom aliases and expiration
- Single region to start, global later
- p99 redirect < 100ms

---

## 2. Numbers to Know Cold

Practice deriving these without a calculator.

```
DAU = 100M
Redirects per user/day = ~10
Total redirects/day = 1B
Read QPS (avg)  = 1B / 86,400 ≈ 11,500 RPS
Read QPS (peak) ≈ 35,000 RPS   (assume 3× average)

Write ratio 1:1000 → Write QPS ≈ 12 WPS (trivial for any DB)

Storage per URL ≈ 500 bytes (short_code + long_url + metadata)
1B URLs × 500B = 500 GB  → single PostgreSQL node is fine
Cache (top 20% of URLs = 80% traffic): 200M × 500B = 100 GB Redis

Base62 codes: 62^8 = 218 trillion → never exhausted at this scale
```

**Key insight to state out loud:** "This system is almost entirely a read problem. Writes are trivially easy — 12 WPS. All my architecture choices are about serving 35,000 redirects/second under 100ms."

---

## 3. Core Entities (say these immediately)

```
Url
  id            BIGINT      (surrogate PK, internal only)
  short_code    VARCHAR(10) UNIQUE NOT NULL   ← the business key
  long_url      TEXT        NOT NULL
  custom_alias  VARCHAR(100)
  expiration_date TIMESTAMP
  created_at    TIMESTAMP
```

Two indexes matter:
1. `UNIQUE INDEX on short_code` — every redirect is a lookup by this key
2. `Partial index on expiration_date WHERE NOT NULL` — cleanup jobs only

---

## 4. API — State It Upfront

```
POST /urls
  Body:    { long_url, custom_alias?, expiration_date? }
  Returns: 201 { short_url, short_code, long_url, ... }
  Errors:  422 (validation), 409 (alias taken)

GET /{short_code}
  Returns: 302 Location: <long_url>
  Errors:  404 (not found), 410 (expired)
```

**Interviewer will always ask: why 302 and not 301?**

> "301 is cached permanently by browsers. Once cached, we lose all server control — we can't update the destination, honour expiration dates, or collect analytics. 302 forces every redirect through our servers."

---

## 5. The One Decision That Defines This Design

**Short code generation** is the crux. Be ready to compare three approaches:

### Option A: Hash-based
```
short_code = base62(SHA256(long_url))[0:8]
```
- ❌ Collisions possible — need retry loop in the write path
- ❌ Same long URL → same short code (idempotency causes ownership issues)
- ❌ Every write needs a DB read to check for collision

### Option B: UUID truncation
```
short_code = base62(UUID)[0:8]
```
- ❌ Collisions still possible (truncated entropy)
- ❌ Still needs collision check

### Option C: Counter + Redis INCRBY batching (recommend this)
```
Redis: INCRBY url:counter 1000 → returns N
Instance owns range [N-999, N] exclusively
short_code = base62Encode(counter_value)   // 8 chars, deterministic
```
- ✅ Globally unique, guaranteed — no collision logic needed
- ✅ 1 Redis call per 1,000 writes (amortised cost ≈ 0)
- ✅ Predictable 8-char length
- ✅ Thread-safe with `AtomicLong` — no lock contention within a batch

**How to explain batching:** "Each app server claims a block of 1,000 counter values atomically from Redis with a single `INCRBY`. It then assigns those 1,000 codes locally using an `AtomicLong` — zero Redis coordination for the next 999 writes. This gives us write throughput limited only by our app server CPU."

---

## 6. Architecture — Draw This

```
                     Client
                       │
               API Gateway / LB
              /         |         \
        App Node    App Node    App Node
           │              │
    ┌──────┴──────┐  ┌────┴──────┐
    │  Redis      │  │ PostgreSQL│
    │  - Cache    │  │ - Primary │
    │  - Counter  │  │ - Replicas│
    └─────────────┘  └──────────┘
```

**Write path:**
```
POST /urls → UrlController → UrlShorteningService
  → ShortCodeGenerator (AtomicLong → Redis INCRBY on batch boundary)
  → INSERT into PostgreSQL
  → evict from Redis cache (safety)
```

**Read path:**
```
GET /{code} → RedirectController → UrlRedirectService
  → Redis GET url:{code}   ← HIT → 302
      MISS
  → PostgreSQL SELECT WHERE short_code = ?
      NOT FOUND → 404
      EXPIRED   → 410
      OK → Redis SET url:{code} EX {ttl} → 302
```

---

## 7. Caching — Common Follow-up Questions

**Q: What's the cache key structure?**
> `url:{short_code}` → `long_url` as a plain string. StringRedisTemplate avoids Java serialisation overhead.

**Q: What TTL do you use?**
> Aligned to URL expiration: if URL expires in 2 hours, TTL = 2 hours. For non-expiring URLs, default 24h TTL. This prevents stale cache hits for expired URLs.

**Q: What eviction policy?**
> `allkeys-lru` — Redis automatically evicts the least recently used entries when memory fills. This naturally keeps hot links resident.

**Q: Why not `@Cacheable`?**
> Spring's `@Cacheable` doesn't support per-entry TTL based on runtime values. We need different TTLs for each URL (aligned to its expiration date), so we use explicit cache-aside with `RedisTemplate`.

**Q: Do you cache 404s (negative caching)?**
> No. The miss rate for real 404s is negligible, and negative caching would break read-your-writes consistency — if we cache "not found" and the URL is created milliseconds later, the cache returns a stale 404.

**Q: What if Redis goes down?**
> Cache miss for every read, all traffic goes to PostgreSQL. At 35,000 RPS this would overwhelm PG. Mitigation: Redis Sentinel for automatic failover (15-30s window), circuit breaker to shed non-critical load during failover.

---

## 8. Database — Common Follow-up Questions

**Q: Why PostgreSQL?**
> ACID guarantees, `UNIQUE` constraint as a safety net for short codes, `TEXT` type with no length limit for long URLs, partial indexes for expiry queries. Any RDBMS works given our low write rate.

**Q: Why not NoSQL (DynamoDB, Cassandra)?**
> Our access pattern is a single primary key lookup (`short_code`) — NoSQL would work fine too. PostgreSQL is simpler operationally. If write scale grew to 100,000+ WPS, Cassandra's write throughput would be compelling.

**Q: How would you shard if needed?**
> Shard by first character of `short_code` — 62 possible shards. The counter generator would partition ranges across shards. But 500GB fits on one node; sharding adds operational complexity for no current benefit.

**Q: Why not use the DB sequence for short code generation?**
> PostgreSQL sequence requires a round-trip to the primary (5ms) for every write. Redis INCRBY is 0.3ms and with batching the amortised cost per code is ~0.0003ms.

---

## 9. Availability — Common Follow-up Questions

**Q: How do you achieve 99.99%?**
> Eliminate every SPOF:
> 1. Multiple stateless app nodes behind a load balancer
> 2. Redis Sentinel (3 sentinels) for automatic failover
> 3. PostgreSQL streaming replication + Patroni/RDS Multi-AZ for failover
> 4. Health checks on every component exposed via `/actuator/health`

**Q: What happens if Redis Sentinel fails over?**
> During the failover window (~15-30s): writes fail (can't generate codes), reads degrade to PostgreSQL-only (slower but functional). This is acceptable — read availability is preserved.

**Q: What happens if the counter Redis key is lost?**
> App servers claim a new batch starting from the current counter value. There may be a gap in used counter values (some codes skipped), but no collisions. The `UNIQUE` constraint on `short_code` provides a DB-level safety net even if the app layer misbehaves.

---

## 10. Scaling Follow-up Questions

**Q: How do you handle a viral link (10× traffic spike)?**
> CDN caching. A CDN edge node (Cloudflare, Fastly) can cache the `302 Location` response with a short TTL (30s). For the top 0.01% of links, nearly all traffic is served from CDN edge with ~5ms latency. This is the most cost-effective scaling lever for read spikes.

**Q: How do you scale to multiple regions (global < 100ms)?**
> Deploy full stacks in US, EU, AP. Each region gets a disjoint counter range (US: 0–10¹², EU: 10¹²–2×10¹², AP: 2×10¹²–3×10¹²). No cross-region coordination needed for code generation. PostgreSQL can use async replication for reads; writes go to the nearest regional primary.

**Q: At what point would you shard PostgreSQL?**
> At ~5TB dataset or ~10,000 WPS. Neither is likely at 1B URLs with our write rate. For read scale, add read replicas first.

---

## 11. What Interviewers Actually Want to Hear

These are the signals that separate good from great answers:

1. **State the read/write ratio first.** "1000:1 — this is a read-heavy system. My job is to serve redirects fast."

2. **Compare code generation options before picking one.** Don't just say "I'd use a counter." Show you know why hashing fails.

3. **Explain 302 vs 301 without being asked.** It demonstrates awareness of HTTP semantics and real-world tradeoffs.

4. **Mention cache TTL alignment.** Most candidates just say "use Redis." Saying TTLs align to URL expiration shows operational depth.

5. **Know your numbers.** 35K RPS peak reads, 500GB storage, 100GB cache. Derive them out loud.

6. **Proactively address failure modes.** What happens when Redis is down? When PostgreSQL failover is happening? Availability isn't just uptime — it's graceful degradation.

7. **Mention CDN for hot links.** Shows you've thought about traffic distribution beyond your own servers.

---

## 12. Common Mistakes to Avoid

| Mistake | Why it's wrong |
|---------|----------------|
| Using MD5 hash without mentioning collision handling | MD5 collisions are rare but not zero; need retry logic |
| Using 301 redirect | Permanent browser cache loses control over the link |
| Caching without TTL alignment | Expired URLs return 302 instead of 410 |
| Not having a DB-level UNIQUE constraint | App logic bugs can create duplicate short codes |
| Proposing to shard immediately | 500GB fits on one node; premature complexity |
| Over-indexing (indexing long_url) | TEXT columns can't always be B-tree indexed; Zipf means most URLs are cold anyway |
| Storing short codes in the DB sequence | PG sequence adds 5ms latency per write; Redis batching is far cheaper |
| Ignoring Redis failure mode | Redis going down shouldn't bring down redirects |

---

## 13. Follow-up: Extensions the Interviewer May Ask

| Extension | Key points |
|-----------|------------|
| **Analytics (click counts)** | Write-heavy secondary path; async (Kafka → consumer → analytics DB). Don't write sync in redirect path or you'll blow the 100ms SLA. |
| **User authentication** | Add `user_id FK` to urls table; JWT middleware at API gateway |
| **Rate limiting** | Token bucket at API gateway per IP/user; Redis INCR with expiry is the standard implementation |
| **Custom domains** | Add `domain` column to urls table; DNS CNAME to your infrastructure; routing layer checks Host header |
| **QR codes** | Generate on-the-fly in the POST /urls response; store or regenerate on demand (cheap) |
| **Bulk shortening** | `POST /urls/batch` with array body; same service, loop internally; return partial success |
| **URL validation** | HEAD request to long_url before accepting? Adds latency. Reject malformed URLs with regex, defer reachability check to background job. |

---

## 14. One-Page Cheat Sheet

```
PROBLEM: Shorten URLs, redirect users fast
SCALE:   1B URLs, 100M DAU, 35K RPS reads, 12 WPS writes
LAT:     <100ms redirect (p99)

ENTITIES: urls(id, short_code UNIQUE, long_url TEXT, expiry, created_at)

API:
  POST /urls  → 201 {short_url}
  GET /{code} → 302 Location: <long_url>
              → 404 not found | 410 expired

CODE GEN: Redis INCRBY batching (batch=1000) → base62(counter) → 8 chars
  WHY: Guaranteed unique, no collisions, amortised cost ≈ 0

READ PATH: Redis cache-aside → PG fallback
  TTL aligned to URL expiration | LRU eviction | no negative caching

HA:
  App: stateless multi-node behind LB
  Redis: Sentinel (3 sentinels, auto-failover ~20s)
  PG: streaming replication + Patroni failover

SCALE:
  Reads: more app nodes (stateless) → Redis cluster → PG read replicas → CDN
  Writes: single PG primary is fine forever (12 WPS)

302 not 301: keeps server control, enables expiry, enables analytics
```

---

*Study this alongside the codebase in `src/` — every decision here maps directly to a class or config.*
