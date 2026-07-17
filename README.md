# System Design Reference Implementations

Runnable Java (Spring Boot) implementations of classic system design problems — each paired with a full design document, an interview study guide, and a code walkthrough.

---

## Projects

Eleven designs are implemented, listed alphabetically. Every project ships three companion documents (design doc, study guide, code walkthrough) and follows the same section format below.

### [Bitly — URL Shortener](./bitly/)

> Design a URL shortening service that maps long URLs to unique short codes and redirects at scale.

**Core problem:** Serve an overwhelmingly read-heavy workload — ~1B URLs, ~35,000 redirects/sec peak at a 1000:1 read/write ratio — resolving each redirect in under 100ms p99 at 99.99% availability, which hinges on collision-free code generation and fast hot-key reads.

**Key design decisions:**
- Counter-based short codes — each instance claims a 1,000-code range via Redis `INCRBY` and base62-encodes to 8 chars: collision-free, ~1 Redis call per 1,000 writes, no distributed lock
- Cache-aside with Redis, per-entry TTL aligned to each URL's expiration; all Redis calls try/catch-wrapped to degrade to DB-only
- PostgreSQL source of truth — UNIQUE B-tree index on `short_code`, partial index on `expiration_date` for cleanup
- HTTP 302 (not 301) so every click reaches the server, preserving expiration control and click analytics

**Stack:** Spring Boot 3.2 · PostgreSQL · Redis · Spring Data JPA · Lettuce

| Document | Description |
|----------|-------------|
| [DESIGN.md](./bitly/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./bitly/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./bitly/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Dropbox — File Storage & Sync](./dropbox/)

> Design a cloud file storage and sync service that ingests large chunked uploads and propagates changes across devices in real time.

**Core problem:** Store and sync 10 PB across ~1 trillion files for 100M DAU at ~5,800 uploads/sec (58 GB/sec) without app servers ever proxying file bytes, while serving cached downloads in <100ms and propagating cross-device changes within seconds at 99.99% availability.

**Key design decisions:**
- Direct-to-S3 multipart upload via presigned PUT URLs (8 MB chunks) — the app server handles only `/initiate` and `/complete`, never file bytes
- Real-time sync over WebSocket backed by Redis Pub/Sub (`file-changes:{uid}`), with `GET /files/changes?since=` as the reconnect fallback
- PostgreSQL metadata (file / chunk / share tables) for ACID upload-completion atomicity; DynamoDB deferred until metadata exceeds ~10 TB
- Downloads via short-lived presigned / CDN-signed URLs served from edge PoPs (<100ms on cache hits)
- Per-chunk SHA-256 enables resumable uploads and future dedup / delta-sync

**Stack:** Spring Boot 3.2 · PostgreSQL · Redis (Pub/Sub) · AWS S3 · Spring WebSocket

| Document | Description |
|----------|-------------|
| [DESIGN.md](./dropbox/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./dropbox/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./dropbox/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Facebook — News Feed](./facebook-newsfeed/)

> Design a personalized, real-time news feed that ranks and delivers posts from followed users at social-network scale.

**Core problem:** Serve 10B feed reads/day (~115K QPS) to 500M DAU while fanning out 100M posts/day to ~300 followers each within a p95 of 500ms — and avoid a fanout explosion when celebrity accounts (>1M followers) post.

**Key design decisions:**
- Hybrid fanout — fanout-on-write pushes `postId` into each follower's Redis ZSET for normal users; celebrities skip the write and have their recent posts pulled on read, with automatic promotion past a follower threshold to avoid the write explosion
- Fanout runs after commit via a `PostCreatedEvent` handled by `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` — post creation never blocks on propagation
- Feed ZSET (`feed:{userId}`, score = `createdAt` ms) capped at 1,000 posts with a 1-day TTL, plus a 1h post cache; cold-start DB fallback and try/catch-wrapped Redis for graceful degradation
- Read-time relevance ranking — the News Feed differentiator: `score = recencyDecay · engagementBoost · affinity`, applied when the feed is read rather than at write time

**Stack:** Spring Boot 3.2 · H2 (in-memory) · Redis · Spring @Async

| Document | Description |
|----------|-------------|
| [DESIGN.md](./facebook-newsfeed/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./facebook-newsfeed/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./facebook-newsfeed/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Google News — Feed Aggregator](./googlenews/)

> Design a news aggregation platform that continuously crawls thousands of sources, deduplicates content, and delivers personalized, low-latency feeds.

**Core problem:** Aggregate ~100,000 articles/day from 10,000+ RSS/Atom sources for 500M MAU, deduplicate near-identical stories, and serve personalized feeds at ~30,000 peak QPS (p95 <500ms) with <10-minute article freshness.

**Key design decisions:**
- Polling crawler (Spring `@Scheduled` + ROME) fetching RSS/Atom every 5–15 min per source, adaptive by source volume
- SHA-256 content-hash dedup over a rolling 7-day window (MinHash/shingling noted as the near-duplicate upgrade)
- Two-stage ranking — interest-based candidate retrieval, then scoring by freshness decay · source trust · topic match · engagement
- Redis-backed Spring Cache with per-cache TTLs (feed 5 min, article 30 min) to hold p95 feed latency <500ms
- Elasticsearch full-text search and read-replica/sharding are specified in the design (not yet implemented)

**Stack:** Spring Boot 3.2 · PostgreSQL · Redis · ROME RSS Parser · Spring Scheduling

| Document | Description |
|----------|-------------|
| [DESIGN.md](./googlenews/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./googlenews/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./googlenews/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [GoPuff — Local Delivery Service](./gopuff/)

> Design a local on-demand delivery service where users order items for ~1-hour delivery from nearby distribution centers.

**Core problem:** Guarantee that two customers never reserve the same physical unit (strong consistency on orders) while serving read-heavy availability queries at ~20K QPS with p99 <100ms, across 10K distribution centers and 10M orders/day.

**Key design decisions:**
- `SERIALIZABLE` isolation + pessimistic `SELECT ... FOR UPDATE` on contended inventory rows — deterministically serializes hot-item checkouts, no optimistic-retry storms
- Orders and inventory co-located in one PostgreSQL DB so a single local ACID transaction gives all-or-nothing atomicity (no saga / 2PC)
- Inventory modeled as `quantity` + `reserved_quantity` with CHECK constraints — reversible, auditable, DB-enforced no-oversell
- Redis availability cache keyed on coordinate-rounded (2 dp ≈ 1.1 km) + item IDs, 60s TTL, evict-on-order, graceful DB fallback
- In-memory Haversine DC discovery (≤60 miles) with single-DC all-or-nothing fulfillment

**Stack:** Spring Boot 3.2 · PostgreSQL (SERIALIZABLE) · Redis · Spring Data JPA

| Document | Description |
|----------|-------------|
| [DESIGN.md](./gopuff/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./gopuff/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./gopuff/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Instagram — Photo Sharing Platform](./instagram/)

> Design a photo/video sharing platform with a follow graph and a reverse-chronological feed of followed users.

**Core problem:** Serve reverse-chronological feeds to 500M DAU at p99 <500ms while ingesting 100M posts/day (~1,157/sec), where naive fanout-on-write detonates on celebrity accounts (100K–10M+ followers).

**Key design decisions:**
- Hybrid fanout — push `postId` into each follower's Redis ZSET (`insta:feed:{userId}`) for users under 100K followers; skip celebrities and pull their last-24h posts on read
- Fanout runs after commit via a `PostCreatedEvent` handled by `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` — post creation never blocks on propagation
- Feed ZSET (score = `createdAt` ms) capped at 1,000 posts with 1-day TTL, plus a 1h post cache hydrated cache-aside
- Two-phase presigned-URL upload — client PUTs media directly to a (simulated) CDN, then `/complete` flips status to `UPLOADED`
- Automatic celebrity promotion/demotion at a 100K-follower threshold via atomic conditional SQL; cold-start DB fallback on empty cache

**Stack:** Spring Boot 3.2 · H2 (in-memory) · Redis · Spring @Async

| Document | Description |
|----------|-------------|
| [README.md](./instagram/README.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./instagram/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./instagram/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [LeetCode — Online Coding Platform](./leetcode/)

> Design a coding platform with problem browsing, sandboxed code execution, competitions, and real-time leaderboards.

**Core problem:** Safely execute untrusted user code at scale — isolated per submission (256 MB RAM, 50% CPU, no network, 5s timeout) — returning results within 5s for 100K concurrent users and 10K concurrent contest submissions.

**Key design decisions:**
- Sandboxed execution via the Docker Java API — per-container CPU/memory limits, read-only FS, no network, 5s hard timeout, auto-teardown
- Real-time leaderboard as a Redis ZSET (score = `problemsSolved·1e6 − timeMs/1000`) — O(log N) updates, clients poll top-N every 5s
- Non-blocking submissions via a Spring `@Async` thread pool (core 10, max 50, queue 500) so API threads never block on execution
- H2 in-memory store (JPA, create-drop) for problems, test cases, submissions, and competitions
- Redis-backed Spring Cache (10-min TTL) for problem metadata

**Stack:** Spring Boot 3.2 · H2 (in-memory) · Redis · Docker Java API · Spring @Async

| Document | Description |
|----------|-------------|
| [README.md](./leetcode/README.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./leetcode/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./leetcode/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Ticketmaster — Event Ticketing](./ticketmaster/)

> Design an event ticketing platform that handles seat reservations with zero double-booking under high concurrency.

**Core problem:** Guarantee 100% no-double-booking under extreme bursty contention — ~500K concurrent users spiking to ~100K req/sec at sale start — while keeping hold latency p95 <2s and 99.99% availability.

**Key design decisions:**
- Pessimistic row locking (`@Lock(PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`) + `SERIALIZABLE` isolation on the hold path — makes double-booking impossible on hot seats
- Event-level distributed lock in Redis via a hand-rolled `SETNX` (token + TTL, owner-only release) to serialize holds across stateless instances
- Waiting room as a Redis sorted set scored by join time, with a `@Scheduled` admitter (100 users / 10s) throttling the surge (FIFO)
- Two-phase hold-then-pay — seats held 5–10 min; a `@Scheduled` sweep every 30s releases expired holds and restores inventory
- Denormalized `availableSeats` counter + idempotency-keyed payment confirmation (payment gateway is stubbed)

**Stack:** Spring Boot 3.2 · PostgreSQL (SERIALIZABLE) · Redis · Spring Scheduling

| Document | Description |
|----------|-------------|
| [DESIGN.md](./ticketmaster/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./ticketmaster/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./ticketmaster/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Tinder — Dating App](./tinder/)

> Design a dating application with profile matching, geospatial discovery, and swipe-based interactions.

**Core problem:** Deliver low-latency, location-aware profile discovery and mutual-match detection at 75M users / 10M DAU — 1.6B swipes/day (~18K writes/sec) — with discovery p95 <500ms and the nearby-user query <100ms.

**Key design decisions:**
- Geospatial discovery via a PostgreSQL Haversine SQL query filtering distance, age, gender, and already-swiped in one pass (design proposes Redis `GEORADIUS`)
- O(1) mutual-like detection — each LIKE checks the reverse swipe via a UNIQUE `(swiper, target, direction)` index, creating a `Match` only on a hit
- Recommendation ranking by distance · last-active recency · profile completeness
- Dedicated normalized `Match` table with user-ID sharding and Redis-cached profiles/feeds
- Real-time chat over WebSocket/STOMP is specified in the design (not yet implemented)

**Stack:** Spring Boot 3.2 · PostgreSQL · Redis · Spring Data JPA

| Document | Description |
|----------|-------------|
| [DESIGN.md](./tinder/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./tinder/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./tinder/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [WhatsApp — Messaging Platform](./whatsapp/)

> Design a real-time messaging platform with group chats, offline message delivery, and multi-device support.

**Core problem:** Deliver messages in real time (<500ms for online users) while guaranteeing offline delivery, across 200M concurrent WebSocket connections at 40K messages/sec — routing between stateful chat servers and durably storing undelivered messages per device.

**Key design decisions:**
- WebSocket over persistent TCP with an in-memory `userId→session` map per chat server (~50ms push vs 200–500ms polling)
- Redis Pub/Sub cross-server routing with adaptive partitioning — per-user channels for small groups, per-chat channels for large (>25)
- Per-device Inbox table for durable offline delivery — message + inbox rows in one transaction, ACK deletes the row; up to 3 devices, 30-day TTL
- Monotonic per-sender sequence numbers piggybacked on 30s heartbeats so clients detect gaps and re-request missing ranges
- Last-seen persisted only on disconnect (online status served from memory) to avoid heartbeat write amplification

**Stack:** Spring Boot 3.2 · H2 (in-memory) · Redis (Pub/Sub) · Spring WebSocket

| Document | Description |
|----------|-------------|
| [README.md](./whatsapp/README.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./whatsapp/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./whatsapp/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Yelp — Local Business Search](./yelp/)

> Design a local business search and review platform with geospatial proximity search and review aggregation.

**Core problem:** Serve low-latency geospatial proximity search over 500M businesses at 1B searches/day (~11.6K QPS) with p99 <100ms — finding everything within a radius without O(N) scans while filtering by category, rating, and price.

**Key design decisions:**
- Two-phase geospatial search — a cheap bounding-box B-tree pre-filter, then a precise Haversine post-filter on the small candidate set (no O(N) scan)
- Geohash indexing at precision 5 (~4.9 km cells) plus the 8 neighbor cells to avoid boundary artifacts
- Denormalized `starRating` / `reviewCount` recalculated on each review write — avoids `AVG` GROUP BY over 10M reviews at read time
- Redis result cache keyed on 3-decimal-rounded coordinates (~111 m), 5-min TTL, with detail eviction on a new review
- One-review-per-user via a service-layer check + UNIQUE `(businessId, userId)` constraint

**Stack:** Spring Boot 3.2 · H2 (in-memory) · Redis · Spring Data JPA

| Document | Description |
|----------|-------------|
| [README.md](./yelp/README.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./yelp/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./yelp/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

---

## Document Structure

Every project ships with three documents:

**`DESIGN.md`** (or **`README.md`** in the newer projects) — the system design document. Start here for architecture.
- Problem statement & functional/non-functional requirements
- Capacity estimation (QPS, storage, bandwidth)
- Core entities & data model
- API design
- High-level architecture (ASCII diagrams)
- Deep dives: the key hard problems (code generation, caching, availability, scaling)
- Trade-offs & alternatives considered

**`STUDY_GUIDE.md`** — interview preparation reference.
- Clarifying questions to ask the interviewer (and why each matters)
- Numbers to derive cold (QPS, storage — with working)
- The one central design decision explained with 3 compared options
- Common follow-up questions as Q&A
- What interviewers want to hear vs common mistakes
- Extension questions (auth, analytics, rate limiting, etc.)
- One-page cheat sheet

**`CODE_WALKTHROUGH.md`** — code tour for someone new to the codebase.
- Suggested reading order
- Package map with one-line purpose per file
- Step-by-step flow traces (write path, read path)
- Why key decisions were made at the code level
- Dependency flow diagram
- Key invariants to know

---

## Running Any Project

Each project is a standalone Maven module — run it with `cd <project> && mvn spring-boot:run`.

**In-memory projects** (Facebook, Instagram, LeetCode, WhatsApp, Yelp) run on H2 with no external database. They only need Redis if you want the caching / pub-sub paths exercised:

```bash
docker run -d --name redis -p 6379:6379 redis:7
cd <project>
mvn spring-boot:run
```

**PostgreSQL-backed projects** (Bitly, Dropbox, Google News, GoPuff, Ticketmaster, Tinder) need PostgreSQL and Redis:

```bash
# PostgreSQL
docker run -d --name pg -e POSTGRES_DB=<project> -e POSTGRES_USER=<project> \
  -e POSTGRES_PASSWORD=<project> -p 5432:5432 postgres:15

# Redis
docker run -d --name redis -p 6379:6379 redis:7

# Run
cd <project>
mvn spring-boot:run
```

A few projects have extra local dependencies — Dropbox uses S3 (LocalStack works for local dev) and LeetCode needs a running Docker daemon for code execution. See each project's design doc for specifics.

---

## Coming Soon

- **Pastebin** — text storage, expiry, syntax highlighting
- **Twitter / X** — fan-out on write vs read, timeline generation
- **Uber** — geospatial indexing, ride matching, real-time location updates
- **Netflix** — CDN design, video chunking, adaptive bitrate

---
