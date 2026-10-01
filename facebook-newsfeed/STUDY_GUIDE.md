# Facebook News Feed — Interview Study Guide

## 30-Second Pitch
"Facebook News Feed serves 10B feed requests/day to 500M DAU. Core challenge: fanout at scale—when a user with 1M followers posts, we can't write to 1M feeds synchronously. Solution: hybrid fanout—fanout-on-write for normal users (fast reads), fanout-on-read for celebrities (avoid fanout explosion). Feed stored in Redis sorted sets, ranked by recency + engagement + relationship strength."

## Key Numbers
- **500M DAU**, 10B feed reads/day = **115K QPS**
- **100M posts/day** = 1,150 writes/sec
- **Average 300 followers** → 345K fanout writes/sec
- **Feed latency target**: < 500 ms
- **Fanout latency**: < 5 seconds

## Critical Decision: Fanout Strategy

### Fanout-on-Write (Push)
- Write post → immediately push to all followers' feeds
- ✅ Fast reads (pre-computed)
- ❌ Slow writes (celebrity with 10M followers = 10M writes)
- ❌ Wasted work (90% of users never read their feed)

### Fanout-on-Read (Pull)
- Read feed → query posts from all followed users in real-time
- ✅ Fast writes (no fanout)
- ❌ Slow reads (must query 300 users × recent posts)

### Hybrid (Chosen)
- Normal users (< 1M followers): Fanout-on-write
- Celebrities (> 1M followers): Fanout-on-read
- ✅ Best of both: 99.9% get fast reads, celebrities avoid fanout explosion

## Common Questions

**Q: "How do you handle a celebrity with 100M followers?"**
A: Mark as celebrity (`is_celebrity = true`), skip fanout. At read time, fetch their posts separately and merge with pre-computed feed. Only affects 0.1% of users.

**Q: "What if user follows 5,000 people?"**
A: Limit active followings to 300 most recent. Alternative: Fetch top N most-engaged friends' posts only.

**Q: "How do you rank posts?"**
A: Score = recency × 50% + engagement × 30% + relationship strength × 15% + content preference × 5%. Store score in Redis sorted set.

**Q: "How do you scale Redis?"**
A: Shard by user ID (100 shards, each handles 5M users). Each feed is independent—no cross-shard queries.

## Trade-offs
| Factor | Fanout-on-Write | Fanout-on-Read | Hybrid |
|--------|-----------------|----------------|--------|
| Read latency | < 100 ms | > 1 sec | < 200 ms |
| Write latency | Seconds (fanout) | < 10 ms | < 50 ms |
| Storage | High (all feeds) | Low (posts only) | Medium |
| Complexity | Low | Medium | High |

## Red Flags
❌ "Store entire post in feed" → Too much data, use post IDs  
❌ "Fanout to all followers synchronously" → Blocks for seconds  
❌ "SQL JOIN across 300 friends" → Too slow  
✅ "Redis sorted set with post IDs, async fanout via Kafka"

## Cloud Infrastructure & Sizing (AWS ↔ GCP)

> How you'd actually deploy this. Every AWS service below is paired with its GCP equivalent, and every instance count is derived from this design's own back-of-the-envelope numbers.

### Building blocks this design needs
- **Relational DB** — source of truth for `users`, `posts`, and `friendships` (the graph edges that fanout walks); JPA entities with indexes on `user_id,created_at` and follower/followee.
- **Key-value / cache** — Redis sorted sets hold each user's pre-computed feed (`feed:{userId}`, score = ranking, value = postId); this is the hot path for the 115K read QPS.
- **Object / blob storage** — post media (`mediaUrl` for `IMAGE`/`VIDEO` posts); the DB stores only the reference, the bytes live in a blob store.
- **CDN / edge** — fronts post media (image/video blobs) and static assets, offloading that blob egress from the origin; the design's top-level "CDN + WAF" layer. (The ~46 Gbps figure is *personalized* feed-response JSON served by the API tier — it is largely not CDN-cacheable; the media-blob egress the CDN offloads is a separate, uncomputed stream.)
- **Stream-log / pub-sub / task queue** — decouples post creation from fanout: `POST /posts` returns immediately and drops a `post-created` event that fanout workers consume.

### Managed-service equivalents
| Building block | In this repo (dev) | AWS | GCP | When to reach for it |
|----------------|--------------------|-----|-----|----------------------|
| Relational DB | H2 in-memory (JPA) | RDS / Aurora PostgreSQL | Cloud SQL for PostgreSQL or AlloyDB | Durable posts/users/friendship graph with secondary indexes and read replicas |
| Key-value / cache | Spring Data Redis (local Redis) | ElastiCache for Redis (cluster mode) | Memorystore for Redis Cluster | Pre-computed feeds needing sub-ms ranked reads (sorted sets) at 100K+ QPS |
| Object / blob storage | `mediaUrl` string only (no upload) | S3 | Cloud Storage | Storing/serving image & video blobs cheaply and durably |
| CDN / edge | none (direct serve) | CloudFront (+ WAF) | Cloud CDN (+ Cloud Armor) | Caching media at edge, cutting origin egress and read latency |
| Stream-log / queue | in-JVM `@Async` fanout | MSK (managed Kafka) or MSK Serverless | Managed Service for Apache Kafka, or Pub/Sub | Async, ordered-per-author fanout that survives worker restarts |

### Compute & capacity sizing (from our BOTE numbers)
Driving numbers for this design:
- **Peak feed reads:** ~350K QPS (3× the 115K/s average).
- **Peak post writes:** ~2,300/s (2× the 1,150/s average).
- **Fanout amplification:** 345K feed writes/s; pipelining ~100 commands/round-trip → ~3.45K Redis round-trips/s, while the server still executes ~345K ZADD ops/s.
- **Storage footprint:** 25 TB feed cache (Redis), ~110 TB/yr posts (3× replicated), ~24 TB friendship edges.

| Tier | Driving number | Sizing logic | AWS | GCP |
|------|----------------|--------------|-----|-----|
| App / API tier | ~350K read QPS peak | ~350K QPS ÷ ~5K QPS/node ≈ 70 nodes + ~50% headroom ≈ **105 nodes** | c7g.2xlarge | c3-standard-8 |
| Primary DB (sharded) | 2,300 writes/s + ~110 TB | Shard by `user_id` (design's 100 shards); memory-optimized, writes are modest, storage/graph reads drive it | Aurora PostgreSQL db.r6g.4xlarge/shard | AlloyDB or Cloud SQL db-highmem-16/shard |
| Read replicas | ~70K feed-hydration reads/s (20% miss) | 2–3 replicas/shard to absorb post-body lookups after cache misses | Aurora read replicas db.r6g.2xlarge | AlloyDB read pool / Cloud SQL read replicas |
| Cache (Redis) | 25 TB + ~345K ZADD ops/s | 100 shards × ~250 GB (5M users each); ~3.45K ops/s per node is trivial, RAM is the constraint | ElastiCache cache.r7g.8xlarge × ~100 (cluster mode) | Memorystore for Redis Cluster sized to 25 TB |
| Object store (media) | image/video blobs | Serverless, auto-scales; lifecycle old media to colder tier | S3 (Standard + Glacier IA) | Cloud Storage (Standard + Nearline/Coldline) |
| Streaming (Kafka) | ~2,300 post events/s peak | Low throughput; partition by `author_id` for per-author ordering; 3–6 brokers | MSK kafka.m7g.large × 3–6 (or MSK Serverless) | Managed Service for Apache Kafka, or Pub/Sub |
| Fanout worker fleet | ~345K ZADD ops/s (~3.45K pipelined round-trips/s) | I/O-bound Kafka→Redis-pipeline consumers; ~345K ops/s ÷ ~23K ops/s/worker ≈ **15**, HPA on Kafka lag | m7g.xlarge × ~15 | n2-standard-4 × ~15 |

### Load balancing & edge
- **L7 application LB** in front of the API tier for path routing (`/feed`, `/posts`, `/follow`), TLS termination, and health checks: AWS **Application Load Balancer (ALB)** / GCP **Global External Application Load Balancer**.
- **Global anycast + DNS** for multi-region entry and DDoS absorption: AWS **Global Accelerator** (with Route 53 latency routing) / GCP's global LB is **anycast by default** (with Cloud DNS).
- **CDN fronts image/video blobs and static assets**, offloading that media egress from the origin and keeping bytes near users: AWS **CloudFront** / GCP **Cloud CDN**; edge WAF via AWS **WAF** / GCP **Cloud Armor**. (The ~46 Gbps feed-response egress is personalized per-user JSON served by the API tier — largely non-CDN-cacheable — a separate stream from the media-blob egress the CDN handles.)
- **No WebSocket / long-lived tier:** feed is pull/refresh (near-real-time comes from the <5s fanout, not server push), so there are no sticky sessions — the LB can round-robin/least-connections statelessly, which simplifies scaling and rollouts.

### VMs vs containers vs serverless — the call
Containers. Traffic is steady and very high volume (115K–350K QPS on long-lived JVM services with warm Redis connection pools and JIT), so serverless per-request billing at 10B requests/day plus cold-start latency rules out Lambda / Cloud Functions; the fanout workers are steady Kafka consumers, an equally poor serverless fit. Run both the API tier and the worker fleet on Kubernetes — AWS **EKS** / GCP **GKE** — with HPA (API tier on CPU/RPS, workers on Kafka consumer lag) and node autoscaling (Karpenter / GKE node auto-provisioning) for better bin-packing and rolling deploys than raw EC2 / Compute Engine VMs.
