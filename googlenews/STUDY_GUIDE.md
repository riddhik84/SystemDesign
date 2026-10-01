# Google News System Design — Interview Study Guide

> **Purpose:** This guide helps you prepare for system design interviews by breaking down the Google News design into memorable talking points, common pitfalls, and follow-up questions interviewers ask.

---

## Table of Contents

1. [30-Second Pitch](#1-30-second-pitch)
2. [Requirements Breakdown](#2-requirements-breakdown)
3. [Key Design Decisions](#3-key-design-decisions)
4. [Common Interview Questions](#4-common-interview-questions)
5. [Deep Dive Topics](#5-deep-dive-topics)
6. [Comparison with Similar Systems](#6-comparison-with-similar-systems)
7. [Red Flags to Avoid](#7-red-flags-to-avoid)
8. [Talking Points Checklist](#8-talking-points-checklist)

---

## 1. 30-Second Pitch

**What you say:**

> "Google News is a content aggregation platform serving personalized feeds to 500M users. The system continuously crawls 10K+ news sources via RSS/Atom, deduplicates articles using content hashing, and ranks them based on freshness, user interests, and engagement. Key components: a polling-based crawler, PostgreSQL for articles, Redis for caching, and Elasticsearch for search. The feed generation uses a two-stage ranking system—candidate retrieval followed by personalized scoring."

**Why this works:**
- States the scale upfront (500M users, 10K sources)
- Names the core technologies (PostgreSQL, Redis, Elasticsearch)
- Highlights the main technical challenge (personalization at scale)

---

## 2. Requirements Breakdown

### Functional Requirements (What the System Does)

| Requirement | Interview Talking Point |
|-------------|------------------------|
| **Personalized feed** | "Users follow topics/sources/keywords. Feed ranks articles by relevance + freshness." |
| **Article search** | "Full-text search with filters (topic, date range, source). Elasticsearch handles this." |
| **Continuous crawling** | "Poll RSS/Atom feeds every 5-15 min. Adaptive: increase frequency during breaking news." |
| **Deduplication** | "SHA-256 hash of normalized title+content. Detects exact duplicates within 7-day window." |
| **Trending articles** | "Velocity-based: engagement score ÷ time since published. Cached for 5 minutes." |

### Non-Functional Requirements (How Well It Does It)

| NFR | Target | Why This Matters in Interview |
|-----|--------|-------------------------------|
| **Scale** | 500M MAU, 10K sources | Shows you understand capacity planning |
| **Feed latency** | < 500 ms (p95) | Redis caching, read replicas |
| **Article freshness** | < 10 minutes | Crawl frequency tuning, cache invalidation |
| **Availability** | 99.9% | DB replication, circuit breakers, graceful degradation |
| **Read:Write ratio** | 1000:1 | Heavy read workload → cache everything |

**Common mistake:** Focusing only on functional requirements. Interviewers want to see you balance features with scale, latency, and availability.

---

## 3. Key Design Decisions

### Decision 1: Polling vs. Push for Feed Ingestion

**Chosen: Polling (RSS/Atom every 5-15 min)**

**Tradeoff:**
- ✅ Works with any feed, no publisher cooperation needed
- ❌ 5-15 minute latency, wasted requests if no updates
- Alternative: WebSub (PubSubHubbub) for real-time push, but requires publisher support

**Interview tip:** Mention that you'd use push (WebSub) if publishers supported it, but polling is more universal.

### Decision 2: Deduplication Strategy

**Chosen: Content hash (SHA-256 of normalized text)**

**Tradeoff:**
- ✅ Fast exact match detection
- ❌ Misses near-duplicates (e.g., "Breaking: X" vs. "X Announced")
- Alternative: Fuzzy matching (MinHash, SimHash) catches near-duplicates but slower

**Interview tip:** Start with hash, propose fuzzy matching as a follow-up if interviewer asks "What about near-duplicates?"

### Decision 3: Personalization Approach

**Chosen: Keyword/topic matching + simple scoring**

**Tradeoff:**
- ✅ Simple, explainable, no ML infrastructure
- ❌ Less accurate than deep learning models
- Alternative: Collaborative filtering, embeddings, or deep learning

**Interview tip:** Say you'd start with simple rules for MVP, then iterate to ML if accuracy becomes a bottleneck.

### Decision 4: Caching Strategy

**Chosen: Multi-layer (Redis for feed, Elasticsearch for search)**

**Cache TTL:**
- Feed: 5 minutes
- Article content: 30 minutes
- Trending: 5 minutes

**Invalidation:** Time-based + event-driven (on new article published)

**Interview tip:** Explain the trade-off: shorter TTL = fresher data, higher DB load; longer TTL = stale data, lower latency.

---

## 4. Common Interview Questions

### Q1: "How do you handle a viral news event with 1000 sources publishing the same story?"

**Answer:**
1. **Deduplication catches it:** Content hash identifies duplicates
2. **Trending boost:** Velocity calculation multiplies by `log(duplicateCount)`—more sources = higher ranking
3. **Storage efficiency:** Only store one copy, link all sources via metadata

**Follow-up:** "What if titles are slightly different?"
- Use fuzzy matching (Jaccard similarity on title shingles)
- Threshold: 85%+ similarity → merge

### Q2: "How do you scale to 10 billion articles (10 years of history)?"

**Answer:**
1. **Database sharding:**
   - Time-based: Shard by `published_at` (month or quarter)
   - User-based: Shard user interests by `user_id % N`
2. **Elasticsearch:**
   - Time-based indices: `articles-2026-06`, `articles-2026-07`
   - Drop old indices after 90 days (retention policy)
3. **Archival storage:**
   - Move articles > 90 days to S3/Glacier
   - Keep metadata in DB for analytics

**Interview tip:** Mention that you'd shard by time for articles (append-only, read-recent pattern) and by user for interests (even distribution).

### Q3: "What if Redis goes down?"

**Answer:**
1. **Graceful degradation:** App falls back to database reads
2. **Read replicas handle load:** Feed queries routed to replicas
3. **Circuit breaker:** If DB is overloaded, return cached trending feed or HTTP 503
4. **Redis cluster:** Use Redis Sentinel or Cluster for HA

**Interview tip:** Always have a fallback plan. "Cache failure shouldn't take down the system."

### Q4: "How do you prevent biased or low-quality sources?"

**Answer:**
1. **Trust score (0-100):** Curated per source, influences ranking
2. **Moderation queue:** Flag sources with high user reports
3. **Crawl failure tracking:** Auto-disable sources with 5+ consecutive failures
4. **User feedback:** Allow users to block sources

**Interview tip:** This shows you care about content quality, not just scale.

### Q5: "How do you personalize for a new user with no interests?"

**Answer:**
1. **Cold start problem:** No interests → can't build personalized feed
2. **Solution:** Show trending feed until user follows ≥3 topics/sources
3. **Onboarding flow:** Prompt user to select interests during signup
4. **Implicit signals:** Track clicks/reads to infer interests (future enhancement)

---

## 5. Deep Dive Topics

### 5.1 Two-Stage Ranking

**Stage 1: Candidate Retrieval (500 articles)**
- Query: Articles matching user's topics/sources in last 24 hours
- DB query with indices on `(topics, published_at)`

**Stage 2: Scoring & Ranking**
```
score = freshness * 10 + trustScore * 0.5 + topicRelevance * 5 + log(engagement) * 2
```

**Why two stages?**
- Can't score millions of articles in < 500 ms
- Narrow to 500, then apply expensive scoring

**Interview tip:** If interviewer asks "Why not score everything?", say "DB can't compute complex scores fast—better to pull candidates first."

### 5.2 Cache Invalidation Strategy

**Time-based (TTL):**
- Simple, predictable
- Acceptable staleness for news (5 min old is fine)

**Event-driven:**
- Invalidate `feed:*` on new article insert
- Risk: Cache thrashing if 100 articles/min published

**Hybrid (chosen):**
- 5-min TTL + invalidate on major events (breaking news)
- Best of both worlds

### 5.3 Elasticsearch Index Strategy

**Time-based indices:**
```
articles-2026-01  ←  Jan 2026 articles
articles-2026-02  ←  Feb 2026 articles
...
```

**Benefits:**
- Easy to drop old indices (retention policy)
- Sharding: Each month = 1 index, distributed across cluster

**Alias for searching:**
```
articles-current → [articles-2026-06, articles-2026-07, articles-2026-08]
```

**Interview tip:** Mention index aliases for zero-downtime reindexing.

### 5.4 Database Replication Topology

```
Primary (RW) ──┬──▶ Replica 1 (RO) ── Feed queries
               ├──▶ Replica 2 (RO) ── Search queries
               └──▶ Replica 3 (RO) ── Analytics
```

**Read routing:**
- Writes → Primary
- Feed reads → Replica 1 (via `@Transactional(readOnly=true)`)
- Consistency: Async replication (eventual consistency acceptable)

**Interview tip:** Explain that read-heavy workloads benefit from read replicas, but you accept ~1s replication lag.

---

## 6. Comparison with Similar Systems

### Google News vs. Twitter Feed

| Aspect | Google News | Twitter |
|--------|-------------|---------|
| **Content source** | External RSS/Atom | User-generated posts |
| **Update frequency** | 5-15 min (polling) | Real-time (push via Fanout-on-Write) |
| **Personalization** | Topic/source following | Social graph + interests |
| **Scale** | 10K sources, 100K articles/day | 200M DAU, 500M tweets/day |
| **Key challenge** | Deduplication, crawling | Write amplification, fanout |

**Interview takeaway:** Google News is **pull-based aggregation**, Twitter is **push-based social**.

### Google News vs. Reddit

| Aspect | Google News | Reddit |
|--------|-------------|---------|
| **Ranking** | Freshness + engagement | Upvotes + time (hotness algorithm) |
| **Content moderation** | Trust score per source | Community voting + moderators |
| **Personalization** | User interests | Subreddit subscriptions |

**Interview takeaway:** Reddit is **community-driven**, Google News is **algorithmic curation**.

---

## 7. Red Flags to Avoid

### ❌ Don't Say:

1. **"We'll use MongoDB for everything."**
   - Why bad: No justification for NoSQL vs. SQL. Articles have clear schema → PostgreSQL fits better.
   - Say instead: "Articles have relational structure (source, topics), so PostgreSQL. Consider Cassandra if we need multi-datacenter writes."

2. **"We'll use microservices for each feature."**
   - Why bad: Over-engineering. Monolith is fine at this scale.
   - Say instead: "Start with a monolith, split feed service if it becomes a bottleneck."

3. **"We'll build a custom search engine."**
   - Why bad: Reinventing the wheel.
   - Say instead: "Use Elasticsearch—proven, scales to billions of docs, rich query DSL."

4. **"Cache everything forever."**
   - Why bad: Stale data, memory explosion.
   - Say instead: "5-30 min TTL depending on data type. News is time-sensitive."

5. **"We don't need deduplication."**
   - Why bad: Users see 10 copies of the same story.
   - Say instead: "Content hash for exact dupes, fuzzy matching for near-dupes."

### ✅ Do Say:

- **"Let me clarify requirements first."** (Shows you don't jump to solutions)
- **"Here's the trade-off..."** (Shows critical thinking)
- **"For MVP, I'd do X. Later, we could improve with Y."** (Shows pragmatism)
- **"This won't work because..."** (Shows you can critique your own design)

---

## 8. Talking Points Checklist

Use this checklist to ensure you've covered all key areas in your interview:

### Architecture
- [ ] High-level diagram (API, cache, DB, Elasticsearch, crawler)
- [ ] Data flow: User request → Cache → DB → Ranking → Response
- [ ] Background job: Crawler → Queue → Processor → DB + Elasticsearch

### Data Model
- [ ] Core entities: Article, Source, Topic, UserInterest
- [ ] Indices: `(source_id, published_at)`, `(content_hash)`, `(topics)`
- [ ] Deduplication: Content hash strategy

### APIs
- [ ] `GET /feed?userId={id}` — Personalized feed
- [ ] `GET /trending` — Trending articles
- [ ] `GET /search?q={query}` — Full-text search
- [ ] `POST /users/{id}/interests` — Add interest

### Scaling
- [ ] Horizontal API scaling (stateless, auto-scaling)
- [ ] DB sharding (time-based for articles, user-based for interests)
- [ ] Redis cluster (3 primary + 3 replicas)
- [ ] Elasticsearch index strategy (time-based indices)

### Availability
- [ ] DB replication (1 primary + N replicas)
- [ ] Redis cluster (HA)
- [ ] Circuit breaker (Elasticsearch fallback to DB)
- [ ] Graceful degradation (cache miss → DB, DB overload → trending fallback)

### Performance
- [ ] Caching (Redis): 80% hit rate
- [ ] Two-stage ranking (500 candidates → scored)
- [ ] Read replicas for feed queries
- [ ] Connection pooling (HikariCP)

### Monitoring
- [ ] Metrics: Feed latency, cache hit rate, crawl success rate
- [ ] Alerts: DB replication lag, Elasticsearch cluster health
- [ ] Logging: Structured logs for debugging

---

## Final Interview Tips

1. **Ask clarifying questions first:**
   - "What's the read:write ratio?"
   - "Do we need real-time updates or is 5-min delay OK?"
   - "What's the acceptable staleness for personalization?"

2. **Justify every choice:**
   - Don't just say "Use Redis." Say "Use Redis for caching because 80% of requests hit the same feeds—TTL prevents staleness."

3. **Discuss trade-offs:**
   - "Polling is simple but has latency. Push (WebSub) is faster but requires publisher support."

4. **Think about failure modes:**
   - "If Elasticsearch dies, fall back to PostgreSQL full-text search—slower but available."

5. **Mention monitoring:**
   - "We'll track feed latency, crawl success rate, and cache hit rate in Prometheus."

6. **Start simple, iterate:**
   - "For MVP, I'd use keyword matching. Later, we could train an ML model for better personalization."

---

## Summary

**Key takeaways for your interview:**
- Google News is a **content aggregation + personalization** problem
- Core challenge: **Freshness vs. staleness** (cache TTL tuning)
- Scale: **500M users, 10K sources, 1000:1 read:write ratio**
- Tech stack: **PostgreSQL, Redis, Elasticsearch, RSS crawler**
- Ranking: **Two-stage (retrieval → scoring)**, factors: freshness, trust, relevance, engagement

**Practice saying this out loud:**
> "The system polls 10K RSS feeds every 5-15 minutes, deduplicates using content hashing, and stores articles in PostgreSQL. Personalized feeds are cached in Redis for 5 minutes. We use two-stage ranking: first retrieve 500 candidates matching user interests, then score them by freshness, trust, and engagement. Elasticsearch handles full-text search with time-based indices."

Good luck with your interview! 🚀

---

## Cloud Infrastructure & Sizing (AWS ↔ GCP)

> How you'd actually deploy this. Every AWS service below is paired with its GCP equivalent, and every instance count is derived from this design's own back-of-the-envelope numbers.

### Building blocks this design needs

- **Relational DB** — articles, sources, topics, and user interests are relational (FKs, `@ManyToMany` topics, indexed `(source_id, published_at)` / `content_hash`); PostgreSQL is the primary store.
- **Key-value / cache** — Redis holds `feed:{userId}`, `article:{id}`, `trending:global`, and `user:interests:{userId}` (plus a Caffeine L1 in-process cache) to hit the < 500 ms p95 feed target at an 80% hit rate.
- **Full-text search** — Elasticsearch backs `GET /search` with multi-field boosting, fuzziness, and highlighting; PostgreSQL FTS is the fallback.
- **Stream-log / task queue** — a message queue decouples the crawler from the `ArticleProcessor` (dedupe + parse + index), so ingestion spikes (~50 writes/s during breaking news) don't block the write path.
- **Object / blob storage** — cold archive of articles past the 90-day retention window (metadata stays in the DB), keeping the active relational footprint at ~200 GB.
- **CDN / edge** — serves article images/thumbnails and cacheable GETs close to 500M globally distributed users; also the WAF chokepoint.

### Managed-service equivalents

| Building block | In this repo (dev) | AWS | GCP | When to reach for it |
|----------------|--------------------|-----|-----|----------------------|
| Relational DB | H2 in-memory (tests); PostgreSQL driver wired for prod | RDS for PostgreSQL, or Aurora PostgreSQL | Cloud SQL for PostgreSQL, or AlloyDB | Structured, relational entities with joins and secondary indexes; read-replica fan-out |
| Key-value / cache | `embedded-redis` (tests) + in-JVM Caffeine | ElastiCache for Redis | Memorystore for Redis | Sub-ms hot reads of feeds/trending/articles; 1000:1 read:write ratio |
| Full-text search | `spring-data-elasticsearch` against local ES | OpenSearch Service | No first-party managed Elasticsearch/OpenSearch — Elastic Cloud on GCP, or self-manage on GCE | Relevance ranking, fuzzy match, highlighting over millions of docs |
| Task queue | In-JVM async / direct call (RabbitMQ in the design) | Amazon MQ (RabbitMQ), or SQS | Pub/Sub, or Cloud Tasks | Decouple bursty crawl/ingest from processing; retry + backpressure |
| Object / blob storage | Local disk (none wired in dev) | S3 (Standard-IA → Glacier) | Cloud Storage (Nearline → Coldline) | Cheap cold archive of expired articles |
| CDN / edge | N/A locally | CloudFront + AWS WAF | Cloud CDN + Cloud Armor | Cache images/static and shed L7 attacks near users |

### Compute & capacity sizing (from our BOTE numbers)

Driving numbers for this design (from README/DESIGN capacity + this guide):
- **Peak feed read QPS ≈ 30,000** (2.5B feed requests/day, 2× peak factor).
- **Search QPS ≈ 580** (50M searches/day); **write/ingest ≈ 50/s peak** (1.2/s average, 100K articles/day).
- **Cache hit rate 80%** → only ~6,000 QPS reaches the DB tier on miss.
- **Storage ≈ 200 GB active** (90-day retention) → ~600 GB with 3× replication, plus ~500 GB user interests.

| Tier | Driving number | Sizing logic | AWS | GCP |
|------|----------------|--------------|-----|-----|
| App / API | ~30K peak QPS | ~30K QPS / ~3K QPS per node (cache-backed JSON) ~= 10 nodes + ~50% headroom ~= 15; matches HPA 5→50 | ~15× c7g.xlarge (Graviton) | ~15× c3-standard-4 |
| Primary DB | ~50 writes/s + ~600 GB | Writes are trivial; size for working set + candidate-retrieval reads | db.r6g.2xlarge (RDS/Aurora PostgreSQL) | Cloud SQL PostgreSQL 16 vCPU/64 GB, or AlloyDB primary |
| Read replicas | ~6K read QPS on cache miss | ~6K QPS / 3 ~= 2K QPS each; feed + search + analytics split | 3× db.r6g.2xlarge read replicas | AlloyDB read pool (3), or Cloud SQL read replicas |
| Cache (Redis) | 24K QPS from cache; 4 GB `maxmemory`, LRU | Throughput trivial for Redis; size for hot working set + HA | ElastiCache cache.r7g.large, 3 shards + 3 replicas | Memorystore for Redis Cluster (~13 GB), or Standard HA |
| Search (Elasticsearch) | ~580 search QPS; ~200 GB index | 5 shards / 2 replicas per monthly index; 3 hot data nodes | 3× r6g.xlarge.search (OpenSearch); i4i if disk-bound | Elastic Cloud on GCP, or self-managed 3× n2-highmem-4 on GCE |
| Task queue | ~50 msgs/s peak | Tiny; managed broker or serverless queue | Amazon MQ mq.m5.large ×2, or SQS (serverless) | Pub/Sub (serverless), or Cloud Tasks |
| Object store (archive) | ~730 GB/year expired articles | Lifecycle to cold tier after 90 days | S3 Standard-IA → Glacier | Cloud Storage Nearline → Coldline |
| Async crawler/worker fleet | 10K sources / ~10 min ~= ~17 fetches/s + 1.2 articles/s | I/O-bound RSS polling + dedupe/index; small fleet | 2–3× c7g.large | 2–3× n2-standard-2 |

### Load balancing & edge

- **L7 application LB** for path-based HTTP routing (`/feed`, `/search`, `/articles`), TLS termination, and health checks: **AWS ALB** / **GCP Global External Application Load Balancer**.
- **CDN fronts the LB** for article images and cacheable GETs, and hosts the WAF: **AWS CloudFront + AWS WAF** / **GCP Cloud CDN + Cloud Armor**.
- **No WebSocket / long-lived tier** — feed and search are stateless request/response, so **no sticky sessions** are needed; least-connections/round-robin across any pod is fine (the API is explicitly stateless and autoscaled).
- For multi-region low latency, use global anycast: **AWS Global Accelerator** / GCP's global LB is anycast by default.

### VMs vs containers vs serverless — the call

**Containers on Kubernetes (AWS EKS / GCP GKE).** The traffic is read-heavy and spiky (2× peak) behind a stateless API the design already autoscales with an HPA (5→50 pods), so containers give fast scale-out and tight bin-packing. Just as important, the crawler, `ArticleProcessor`, and trending job are long-running scheduled workers holding persistent PostgreSQL/Redis/Elasticsearch connection pools — a natural fit for Deployments/CronJobs but a poor fit for serverless, where cold starts and execution-time limits (Lambda 15 min) hurt pooled connections and long crawl runs. If you wanted the stateless API alone to be serverless you could host it on **AWS App Runner** / **GCP Cloud Run**, but keep the worker fleet on EKS/GKE.
