# System Design Reference Implementations

Production-quality Java (Spring Boot) implementations of classic system design problems — each paired with a design document, interview study guide, and code walkthrough.

---

## Projects

### [Bitly — URL Shortener](./bitly/)

> Design and implement a URL shortening service like Bitly.

**Core problem:** Generate short codes for long URLs and redirect users with sub-100ms latency at 35,000 reads/second.

**Key design decisions:**
- Counter-based short code generation with Redis `INCRBY` batching (1 Redis call per 1,000 writes)
- Cache-aside pattern with TTL aligned to URL expiration
- HTTP 302 (not 301) to preserve server control and enable expiration

**Stack:** Spring Boot 3.2 · PostgreSQL · Redis · Spring Data JPA · Lettuce

| Document | Description |
|----------|-------------|
| [DESIGN.md](./bitly/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./bitly/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./bitly/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Dropbox — File Storage & Sync](./dropbox/)

> Design and implement a cloud file storage and sync service like Dropbox.

**Core problem:** Upload files up to 50GB reliably, serve downloads with low latency globally, and sync changes across multiple devices in real time.

**Key design decisions:**
- Chunked multipart upload (8MB chunks) with presigned S3 URLs — client uploads directly to S3, bypassing app servers
- CDN-signed URLs for downloads — files served from edge, not origin
- Hybrid sync: WebSocket push for real-time + polling (`/files/changes?since=`) as fallback

**Stack:** Spring Boot 3.2 · PostgreSQL · Redis (pub/sub) · AWS S3 · Spring WebSocket

| Document | Description |
|----------|-------------|
| [DESIGN.md](./dropbox/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./dropbox/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./dropbox/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [GoPuff — Local Delivery Service](./gopuff/)

> Design a local on-demand delivery service where users can order items for delivery within 1 hour from nearby distribution centers.

**Core problem:** Prevent two customers from purchasing the same physical item (strong consistency on orders) while serving availability queries at 20K RPS under 100ms.

**Key design decisions:**
- `SERIALIZABLE` isolation + `SELECT FOR UPDATE` on inventory rows — eliminates double-booking without distributed transactions
- Redis availability cache with coordinate-rounded keys (2 decimal places ≈ 1km) and 60s TTL
- Haversine-based DC discovery (≤60 miles), single-DC fulfillment — reject whole order if any item OOS

**Stack:** Spring Boot 3.2 · PostgreSQL (SERIALIZABLE) · Redis · Spring Data JPA

| Document | Description |
|----------|-------------|
| [DESIGN.md](./gopuff/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./gopuff/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./gopuff/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Google News — Feed Aggregator](./googlenews/)

> Design a news aggregation platform that crawls thousands of sources and delivers personalized feeds to millions of users.

**Core problem:** Aggregate 100K articles/day from 10K+ RSS/Atom feeds, deduplicate content, rank by relevance + freshness, and serve personalized feeds to 500M users with p95 latency < 500ms.

**Key design decisions:**
- Polling-based crawler (every 5-15 min) with adaptive scheduling and exponential backoff on failures
- SHA-256 content hash for exact duplicate detection within 7-day sliding window
- Two-stage ranking: candidate retrieval (500 articles matching interests) → personalized scoring (freshness · trust · relevance · engagement)
- Redis caching with 5-min TTL on feeds, 30-min on article content

**Stack:** Spring Boot 3.2 · PostgreSQL · Redis · Elasticsearch · Rome RSS Parser · Spring Scheduling

| Document | Description |
|----------|-------------|
| [DESIGN.md](./googlenews/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./googlenews/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./googlenews/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Ticketmaster — Event Ticketing](./ticketmaster/)

> Design an event ticketing platform that handles seat reservations with zero double-booking under high concurrency.

**Core problem:** Prevent double-booking when 500K users simultaneously try to reserve seats for high-demand events (Taylor Swift, World Cup), ensuring 100% booking correctness while maintaining sub-2s latency.

**Key design decisions:**
- `SERIALIZABLE` isolation + pessimistic locking (`SELECT FOR UPDATE`) — guarantees exactly one winner per seat
- Distributed Redis locks across API servers for event-level coordination
- 10-minute seat holds with scheduled expiry job (every 30 seconds)
- Idempotent payment processing using booking ID as idempotency key
- Waiting room/queue (Redis sorted set) to handle traffic spikes — admit 100 users per 10 seconds

**Stack:** Spring Boot 3.2 · PostgreSQL (SERIALIZABLE) · Redis · Spring Scheduling

| Document | Description |
|----------|-------------|
| [DESIGN.md](./googlenews/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./googlenews/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./googlenews/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

---

## Document Structure

Every project in this repo ships with three documents:

**`DESIGN.md`** — The system design document. Start here for architecture.
- Problem statement & functional/non-functional requirements
- Capacity estimation (QPS, storage, bandwidth)
- Core entities & data model
- API design
- High-level architecture (ASCII diagrams)
- Deep dives: the key hard problems (code generation, caching, availability, scaling)
- Trade-offs & alternatives considered

**`STUDY_GUIDE.md`** — Interview preparation reference.
- Clarifying questions to ask the interviewer (and why each matters)
- Numbers to derive cold (QPS, storage — with working)
- The one central design decision explained with 3 compared options
- Common follow-up questions as Q&A
- What interviewers want to hear vs common mistakes
- Extension questions (auth, analytics, rate limiting, etc.)
- One-page cheat sheet

**`CODE_WALKTHROUGH.md`** — Code tour for someone new to the codebase.
- Suggested reading order
- Package map with one-line purpose per file
- Step-by-step flow traces (write path, read path)
- Why key decisions were made at the code level
- Dependency flow diagram
- Key invariants to know

---

## Running Any Project

Each project requires **PostgreSQL** and **Redis**. The quickest setup:

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

See the individual project's `DESIGN.md` for project-specific details.

---

## Coming Soon

- **Pastebin** — text storage, expiry, syntax highlighting
- **Twitter / X** — fan-out on write vs read, timeline generation
- **Uber** — geospatial indexing, ride matching, real-time location updates
- **Netflix** — CDN design, video chunking, adaptive bitrate
- **WhatsApp** — message queues, delivery receipts, WebSocket at scale

### [Facebook News Feed](./facebook-newsfeed/)

> Design a social media news feed that aggregates posts from followed users and ranks them by relevance.

**Core problem:** Serve 10B feed requests/day (115K QPS) with sub-500ms latency while handling fanout for users with millions of followers.

**Key design decisions:**
- Hybrid fanout: fanout-on-write for normal users (< 1M followers), fanout-on-read for celebrities
- Redis sorted sets store pre-computed feeds (post IDs + ranking scores)
- Async fanout via Kafka with batched Redis writes (100 per pipeline)
- Multi-factor ranking: recency (50%) + engagement (30%) + relationship strength (15%) + preference (5%)

**Stack:** Spring Boot 3.2 · PostgreSQL · Redis · Kafka (simulated)

| Document | Description |
|----------|-------------|
| [DESIGN.md](./facebook-newsfeed/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./facebook-newsfeed/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./facebook-newsfeed/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [Tinder — Dating App](./tinder/)

> Design a dating application with profile matching, geospatial discovery, and swipe-based interactions.

**Core problem:** Process 2B swipes/day (20M DAU × 100 swipes/user) with strong consistency on match detection (no lost matches) and sub-300ms feed generation.

**Key design decisions:**
- Redis + Lua scripting for atomic match detection — solves race condition when two users swipe right simultaneously
- Geospatial filtering with Haversine distance formula in PostgreSQL (production: Elasticsearch with geo-index)
- Feed pre-computation with 15-min Redis cache — trade freshness for latency
- Normalized match pairs (user1_id < user2_id) — single row per match regardless of swipe order

**Stack:** Spring Boot 3.2 · PostgreSQL · Redis · Spring Data JPA

| Document | Description |
|----------|-------------|
| [DESIGN.md](./tinder/DESIGN.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./tinder/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./tinder/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [LeetCode — Online Coding Platform](./leetcode/)

> Design a coding platform with problem browsing, sandboxed code execution, competitions, and real-time leaderboards.

**Core problem:** Execute 10K concurrent code submissions safely (isolated, untrusted code) within 5 seconds while maintaining a live leaderboard for 100K users during competitions.

**Key design decisions:**
- Docker containers for code execution with strict security (read-only FS, no network, 256MB RAM, 50% CPU, 5s timeout)
- Async processing with thread pool (core: 10, max: 50, queue: 500) — non-blocking API responses
- Redis Sorted Set (ZSET) for O(log N) leaderboard updates — score = problemsSolved × 1M - timeMs/1000
- Auto-scaling worker pool based on queue depth — ~1,667 CPU cores needed for 10K concurrent submissions

**Stack:** Spring Boot 3.2 · H2/JPA · Redis · Docker Java API · Spring @Async

| Document | Description |
|----------|-------------|
| [README.md](./leetcode/README.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./leetcode/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./leetcode/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

### [WhatsApp — Messaging Platform](./whatsapp/)

> Design a real-time messaging platform with group chats, offline message delivery, and media attachments.

**Core problem:** Handle 200M concurrent WebSocket connections and 40K messages/second with <500ms delivery latency, guaranteeing message delivery even for offline users.

**Key design decisions:**
- WebSocket for bidirectional real-time communication (<50ms latency vs. HTTP polling)
- Redis Pub/Sub for lightweight message routing between Chat Servers (per-user channels)
- Inbox pattern for guaranteed delivery — write to DB before ACK, sync on reconnect
- Sequence numbers + heartbeat (30s) for gap detection and missed message recovery
- Per-client Inbox (not per-user) enables multi-device sync for up to 3 devices

**Stack:** Spring Boot 3.2 · H2/JPA · Redis Pub/Sub · Spring WebSocket

| Document | Description |
|----------|-------------|
| [README.md](./whatsapp/README.md) | Full system design — capacity estimates, architecture, deep dives, trade-offs |
| [STUDY_GUIDE.md](./whatsapp/STUDY_GUIDE.md) | Interview prep — clarifying questions, key numbers, decision comparisons, common follow-ups |
| [CODE_WALKTHROUGH.md](./whatsapp/CODE_WALKTHROUGH.md) | Code tour — reading order, flow traces, why each design choice was made in code |

---
