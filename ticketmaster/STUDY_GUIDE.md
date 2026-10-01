# Ticketmaster System Design — Interview Study Guide

> This guide prepares you for system design interviews focused on event ticketing platforms like Ticketmaster, emphasizing concurrency control and preventing double-booking.

## 30-Second Pitch

"Ticketmaster handles 100M bookings/year with 500K concurrent users during high-demand sales. The core challenge is preventing double-booking—two users can't reserve the same seat. We use SERIALIZABLE transaction isolation with pessimistic locking (SELECT FOR UPDATE), distributed Redis locks across servers, and a waiting room queue to handle traffic spikes. Seats are held for 10 minutes with automatic expiry. Payment processing uses idempotency keys to prevent double-charging."

## Key Numbers to Remember

- **Scale**: 100M bookings/year, 10M events/year
- **Peak load**: 500K concurrent users, 100K requests/second (Taylor Swift effect)
- **Consistency**: 0% double-booking rate (strong consistency required)
- **Hold duration**: 10 minutes configurable
- **Availability**: 99.99% (≤52 min downtime/year)
- **Payment timeout**: 10 minutes max

## Critical Design Decision: Preventing Double-Booking

### The Problem
Two users select Seat A-12 simultaneously → both see "available" → both try to book → CONFLICT.

### Solution Layers (Defense in Depth)

**Layer 1: Database SERIALIZABLE Isolation**
```sql
-- PostgreSQL configuration
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
```
Prevents phantom reads—two transactions can't both see the same seat as available.

**Layer 2: Pessimistic Locking**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Seat s WHERE s.id IN :ids")
List<Seat> findByIdInWithLock(@Param("ids") List<String> ids);
```
First transaction locks rows, second waits. Guarantees exactly one winner.

**Layer 3: Distributed Redis Lock**
```java
String lockKey = "booking:event:" + eventId;
RLock lock = redissonClient.getLock(lockKey);
lock.tryLock(5, 10, TimeUnit.SECONDS);
```
Event-level lock prevents concurrent bookings across multiple API servers.

**Layer 4: Optimistic Locking (Backup)**
```java
@Version
private Long version;  // JPA auto-increments on update
```
If pessimistic lock fails, version check catches conflicts.

## Common Interview Questions

### Q1: "Why not use optimistic locking instead of pessimistic?"

**Answer:**
- Optimistic = higher throughput but high retry rate under contention
- Pessimistic = guarantees one winner, better for high-value transactions (tickets)
- Booking is high-contention (Taylor Swift = 500K users fighting for 50K seats)
- Better to wait 100ms than retry 10 times and fail

### Q2: "What if 500K users hit the site simultaneously?"

**Answer: Waiting Room/Queue**
1. Users enter Redis sorted set queue (FIFO by timestamp)
2. Scheduled job admits 100 users every 10 seconds
3. Admitted users get 30-min access token in Redis
4. Prevents site overload: max 10K concurrent bookings vs 500K requests

### Q3: "How do you handle hold expiry?"

**Answer: Two approaches**

**Chosen: Background Job**
```java
@Scheduled(fixedRate = 30000)  // Every 30 sec
public void releaseExpiredHolds() {
    List<Seat> expired = seatRepository
        .findByStatusAndHoldExpiresAtBefore(HELD, now);
    // Release seats, mark bookings expired
}
```
- ✅ Simple, centralized
- ❌ Up to 30-sec delay

**Alternative: Redis TTL + Pub/Sub**
- Set TTL on hold key in Redis
- On expiry, pub/sub triggers release
- ✅ Instant expiry
- ❌ Dual-write complexity (DB + Redis)

### Q4: "How do you prevent double-charging on payment retry?"

**Answer: Idempotency Key**
```java
PaymentIntent intent = stripeClient.charge(
    amount,
    token,
    bookingId  // Idempotency key
);
```
Stripe caches response for same key—retry returns cached result (no double charge).

### Q5: "How do you scale to 100K requests/second?"

**Strategies:**
1. **Waiting room**: Rate-limit to 10K concurrent bookings
2. **Horizontal API scaling**: Stateless services, auto-scale to 200 pods
3. **Read replicas**: Route browse queries to replicas (write to primary)
4. **Database sharding**: Shard by event_id (each shard handles subset of events)
5. **Redis cluster**: Distribute locks/queue/cache across nodes

## Trade-offs

| Decision | Chosen | Alternative | Why Chosen |
|----------|--------|-------------|------------|
| **Locking** | Pessimistic | Optimistic | High contention—better to wait than retry |
| **Hold expiry** | Background job | Redis TTL | Simpler, no dual-write |
| **Waiting room** | Server queue | Client polling | Centralized control, fair FIFO |
| **Payment** | Synchronous | Async | Immediate feedback critical for high-value |
| **DB isolation** | SERIALIZABLE | READ COMMITTED | Prevent phantom reads (double-booking) |

## Red Flags to Avoid

❌ "We'll use eventual consistency" → NO. Booking requires strong consistency.
❌ "We'll check seat availability in application code" → Race condition, guaranteed double-booking.
❌ "We'll use NoSQL for bookings" → Most NoSQL lacks SERIALIZABLE isolation.
✅ "We use SERIALIZABLE + pessimistic locks for bookings, eventual consistency for analytics."

## Talking Points Checklist

- [ ] Strong consistency via SERIALIZABLE + pessimistic locks
- [ ] Distributed Redis locks across API servers
- [ ] 10-minute hold with automatic expiry (scheduled job)
- [ ] Idempotent payment processing
- [ ] Waiting room queue for traffic spike (500K → 10K concurrent)
- [ ] Horizontal API scaling (stateless, auto-scale)
- [ ] Database sharding by event_id
- [ ] Read replicas for browse queries

## Final Tips

1. **Lead with the constraint**: "Booking requires 100% correctness—no double-booking ever."
2. **Justify pessimistic locking**: "High contention + high value = pessimistic wins."
3. **Mention waiting room proactively**: Shows you understand traffic spikes.
4. **Payment idempotency**: Demonstrates production thinking.
5. **Scale gradually**: Start with vertical scaling, then replicas, then sharding.

**Practice saying:** "The core challenge is double-booking prevention. We use SERIALIZABLE isolation with pessimistic row locks, distributed Redis locks for multi-server coordination, and a waiting room to handle traffic spikes."

## Cloud Infrastructure & Sizing (AWS ↔ GCP)

> How you'd actually deploy this. Every AWS service below is paired with its GCP equivalent, and every instance count is derived from this design's own back-of-the-envelope numbers.

### Building blocks this design needs

- **Relational DB (primary + read replicas)** — PostgreSQL with SERIALIZABLE isolation is the source of truth for `seats`, `bookings`, and `events`; its `SELECT ... FOR UPDATE` row locks are what actually prevent double-booking, and replicas serve browse/seat-map reads.
- **In-memory store / cache + coordination (Redis)** — one managed Redis fleet fills three roles the code depends on: Redisson event-level distributed locks (`booking:event:<id>`), the waiting-room FIFO sorted set (`queue:<id>`) plus access tokens, and the seat-availability/event cache.
- **Object / blob storage** — origin store for the generated QR ticket images (`cdn.example.com/qr/tkt_*.png`) and static seat-map assets that the CDN fronts; small objects, elastic, lifecycle-expire after the event.
- **CDN / edge + WAF** — fronts static assets, cacheable seat-map GETs, and QR ticket images, and absorbs the sale-start bot/refresh storm before it reaches the app tier (design names CloudFlare here).

### Managed-service equivalents

| Building block | In this repo (dev) | AWS | GCP | When to reach for it |
|----------------|--------------------|-----|-----|----------------------|
| Relational DB (primary + replicas) | H2 in-memory (tests); PostgreSQL driver for prod | Amazon RDS for PostgreSQL, or Aurora PostgreSQL (+ read replicas) | Cloud SQL for PostgreSQL, or AlloyDB for PostgreSQL (+ read replicas / read pool) | You need ACID + SERIALIZABLE and a single authoritative writer — the booking correctness core |
| Cache + lock + queue (Redis) | `embedded-redis` (tests); Spring Data Redis + Redisson | Amazon ElastiCache for Redis (cluster mode); MemoryDB if you want durable locks/queue | Memorystore for Redis Cluster (sharded, HA); no MemoryDB-style durable equivalent (RDB/AOF persistence only) | Distributed locks, the waiting-room sorted set, and the hot seat-map cache |
| Object / blob storage | local disk / none | Amazon S3 | Google Cloud Storage (GCS) | CDN origin for QR ticket images + static seat-map assets |
| CDN / edge + WAF | none locally (CloudFlare in design) | Amazon CloudFront + AWS WAF | Cloud CDN + Cloud Armor | Serve seat maps/QR at the edge and shed the 100K req/s sale-start spike + bots |

### Compute & capacity sizing (from our BOTE numbers)

Driving numbers for this design (from the study guide + DESIGN.md capacity section):
- **Peak spike:** ~100K requests/sec in the first minute of a sale (mostly absorbed by CDN + waiting room, which caps live booking traffic to ~10K concurrent users).
- **Seat-map reads:** ~50K QPS peak (~1K QPS normal) — the read-heavy driver for the app tier and replicas.
- **Writes:** ~3 bookings/sec normal, spiky under sale start but throttled by the waiting room; single-writer path.
- **Storage:** ~30 TB total (dominated by ~50B seat rows at 3× replication); ~20 Gbps seat-map egress at peak.

| Tier | Driving number | Sizing logic | AWS | GCP |
|------|----------------|--------------|-----|-----|
| App / API tier (stateless Spring Boot) | ~50K QPS peak reads (100K req/s spike shed at edge) | ~50K QPS / ~5K QPS per node ~= 10 nodes + ~50% headroom ~= 15 baseline; HPA 10 -> 200 for sale-start spikes | ~15 × c7g.2xlarge (8 vCPU) on EKS/ECS, HPA @70% CPU | ~15 × c3-standard-8 on GKE, HPA @70% CPU |
| Primary DB (writer, SERIALIZABLE) | ~10K concurrent bookings throttle; ~30 TB; HikariCP 100–200 conns/node | One vertical writer, memory-heavy for buffer cache + lock table; scale up before sharding by `event_id` | db.r6g.4xlarge (16 vCPU/128 GB) RDS, or Aurora r7g | Cloud SQL PostgreSQL 16 vCPU/104 GB, or AlloyDB primary |
| Read replicas | ~50K QPS browse + seat-map reads | 3–5 replicas to offload reads off the writer; route browse/seat-view here | 3 × db.r6g.2xlarge read replicas (or Aurora replicas) | 3 × Cloud SQL read replicas, or AlloyDB read pool |
| Cache + lock + queue (Redis) | 50K QPS cache reads; lock op per hold; ~500K queue entries | 6-node cluster (3 primary/3 replica); seat maps ~50 KB × hot events fit in a few GB; isolate lock vs cache vs queue | ElastiCache cache.r7g.xlarge (~26 GB) cluster | Memorystore for Redis Cluster, ~26 GB (HA replica per shard) |
| Async worker / scheduled jobs | Hold-expiry sweep /30s; admit 100 users /10s per event | Small leader-elected fleet (avoid duplicate sweeps); light CPU, mostly DB/Redis I/O | 2 × m7g.large + EventBridge Scheduler | 2 × n2-standard-2 + Cloud Scheduler / Cloud Run job |
| Object storage (QR + static) | ~KBs per QR × tickets sold; static seat-map assets | Elastic, no capacity planning; lifecycle-expire post-event; served via CDN | Amazon S3 (Standard) | Google Cloud Storage (Standard) |
| CDN / edge | ~20 Gbps seat-map egress; 100K req/s spike | Cache static + seat maps; WAF for bots; global anycast | CloudFront + AWS WAF | Cloud CDN + Cloud Armor |

### Load balancing & edge

- **L7 application load balancer** for HTTP API routing (path-based split to waiting-room vs booking services): AWS Application Load Balancer (ALB) / GCP External Application Load Balancer (global HTTPS LB).
- **Global anycast CDN in front of the LB** to absorb the sale-start spike and serve seat maps + QR images at the edge: AWS CloudFront / GCP Cloud CDN, paired with AWS WAF / Cloud Armor for bot mitigation.
- **No sticky sessions needed, no WebSocket tier.** The app is stateless and the waiting-room access token lives in Redis keyed by `sessionId`, so any node can serve any request. The waiting room uses short polling (`GET /waiting-room/status`), not long-lived WebSockets, so there is no long-connection handling to configure at the LB — a normal round-robin L7 target group is sufficient.

### VMs vs containers vs serverless — the call

Use **containers on Kubernetes** — Amazon EKS (or ECS) on AWS, GKE on GCP. The traffic shape is extreme spike over near-idle (100K req/s at 10 AM, ~3 bookings/sec the rest of the day), the services are stateless JVMs, and DESIGN.md already defines a Kubernetes HPA (10 -> 200 pods), so containers give fast horizontal autoscale off a warm pool without serverless cold-starts — which matter because the booking path holds HikariCP connection pools to Postgres that don't survive per-request Lambda/Cloud Function lifecycles. Serverless still fits the periodic workers well (AWS EventBridge + ECS/Fargate task, or GCP Cloud Scheduler + Cloud Run job) for the hold-expiry and admit-users sweeps, while state stays in managed RDS/Cloud SQL + ElastiCache/Memorystore.
