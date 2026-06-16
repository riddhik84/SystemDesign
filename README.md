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
