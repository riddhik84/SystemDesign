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
