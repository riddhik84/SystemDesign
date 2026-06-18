# Ticketmaster — Code Walkthrough

> This guide explains the Spring Boot implementation, focusing on how we prevent double-booking and handle concurrent seat reservations.

## Project Structure

```
ticketmaster/
├── model/              # 7 JPA entities
│   ├── Event.java
│   ├── Venue.java
│   ├── Section.java
│   ├── Seat.java               # @Version for optimistic locking
│   ├── Booking.java
│   ├── BookedSeat.java
│   └── WaitingRoomEntry.java
├── repository/         # 5 Spring Data repos
│   ├── EventRepository.java
│   ├── SeatRepository.java     # Pessimistic locking queries
│   ├── BookingRepository.java
│   └── WaitingRoomRepository.java
├── service/            # 6 services
│   ├── BookingService.java     # Core: hold seats logic
│   ├── PaymentService.java     # Idempotent payment
│   ├── HoldExpiryService.java  # Scheduled job
│   ├── EventService.java
│   ├── WaitingRoomService.java # Queue management
│   └── RedisLockService.java   # Distributed locks
├── controller/         # 3 REST APIs
│   ├── EventController.java
│   ├── BookingController.java
│   └── WaitingRoomController.java
└── config/
    ├── JpaConfig.java          # @EnableJpaAuditing
    └── RedisConfig.java
```

## Critical Path: Holding Seats

### Flow: User selects seats → Hold for 10 min → Pay → Confirmed

**BookingService.holdSeats():**

```java
@Transactional(isolation = Isolation.SERIALIZABLE)
public BookingResponse holdSeats(HoldRequest request) {
    // 1. Validate event is on sale
    Event event = eventRepository.findById(request.getEventId())
        .orElseThrow();
    
    // 2. Acquire distributed lock (Redis)
    String lockKey = "booking:event:" + event.getId();
    return redisLockService.executeWithLock(lockKey, 10, () -> {
        
        // 3. Fetch seats with pessimistic lock (SELECT FOR UPDATE)
        List<Seat> seats = seatRepository.findByIdInWithLock(request.getSeatIds());
        
        // 4. Validate all available
        for (Seat seat : seats) {
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new IllegalStateException("Seat unavailable");
            }
        }
        
        // 5. Hold seats
        Instant holdExpiry = Instant.now().plus(event.getHoldTimeMinutes(), MINUTES);
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.HELD);
            seat.setHeldByUserId(request.getUserId());
            seat.setHoldExpiresAt(holdExpiry);
        }
        seatRepository.saveAll(seats);
        
        // 6. Create PENDING booking
        Booking booking = Booking.builder()
            .userId(request.getUserId())
            .event(event)
            .status(BookingStatus.PENDING)
            .expiresAt(holdExpiry)
            .build();
        bookingRepository.save(booking);
        
        // 7. Decrement available seats (denormalized counter)
        event.setAvailableSeats(event.getAvailableSeats() - seats.size());
        eventRepository.save(event);
        
        return buildResponse(booking);
    });
}
```

**Why this prevents double-booking:**
1. **SERIALIZABLE isolation**: No two transactions see the same seat as available
2. **Pessimistic lock**: `findByIdInWithLock()` → `SELECT ... FOR UPDATE` → first transaction locks rows
3. **Redis distributed lock**: Coordinates across multiple API servers
4. **Optimistic lock backup**: `@Version` on Seat catches any race condition

## Pessimistic Locking Implementation

**SeatRepository.java:**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Seat s WHERE s.id IN :ids")
List<Seat> findByIdInWithLock(@Param("ids") List<String> ids);
```

**Generated SQL:**
```sql
SELECT * FROM seats WHERE id IN (?, ?, ?)
FOR UPDATE;
```

**Effect:**
- User A's transaction locks Seat A-12
- User B's transaction **blocks** until A commits/rollsback
- Only one succeeds

## Redis Distributed Lock

**RedisLockService.java:**
```java
public <T> T executeWithLock(String lockKey, int timeout, Supplier<T> task) {
    String lockValue = UUID.randomUUID().toString();
    
    // Acquire lock (SETNX with TTL)
    boolean acquired = redisTemplate.opsForValue().setIfAbsent(
        lockKey, lockValue, Duration.ofSeconds(timeout)
    );
    
    if (!acquired) {
        throw new IllegalStateException("Could not acquire lock");
    }
    
    try {
        return task.get();  // Execute critical section
    } finally {
        // Release lock (only if we still hold it)
        String currentValue = redisTemplate.opsForValue().get(lockKey);
        if (lockValue.equals(currentValue)) {
            redisTemplate.delete(lockKey);
        }
    }
}
```

**Why event-level lock?**
- Prevents concurrent bookings for same event across multiple servers
- Coarse-grained but simple
- Better approach: shard by section (lock per section)

## Payment Processing

**PaymentService.confirmBooking():**
```java
@Transactional
public BookingResponse confirmBooking(String bookingId, PaymentRequest request) {
    Booking booking = bookingRepository.findById(bookingId).orElseThrow();
    
    // Check not expired
    if (Instant.now().isAfter(booking.getExpiresAt())) {
        throw new IllegalStateException("Booking expired");
    }
    
    try {
        // Call payment gateway (Stripe) with idempotency key
        String paymentId = processPayment(request, bookingId);
        
        // Mark booking confirmed
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPaymentId(paymentId);
        bookingRepository.save(booking);
        
        // Mark seats as BOOKED (no longer held)
        List<Seat> seats = booking.getSeats().stream()
            .map(BookedSeat::getSeat)
            .peek(seat -> seat.setStatus(SeatStatus.BOOKED))
            .collect(Collectors.toList());
        seatRepository.saveAll(seats);
        
        return buildResponse(booking);
        
    } catch (Exception e) {
        // Payment failed → release seats
        releaseSeats(booking);
        booking.setStatus(BookingStatus.FAILED);
        throw new RuntimeException("Payment failed");
    }
}
```

**Idempotency:**
```java
private String processPayment(PaymentRequest request, String idempotencyKey) {
    // In production: call Stripe API
    // Stripe.charge(amount, token, idempotencyKey);
    // If retry with same key → Stripe returns cached response
    return "pay_" + idempotencyKey;
}
```

## Hold Expiry (Background Job)

**HoldExpiryService.java:**
```java
@Scheduled(fixedRate = 30000)  // Every 30 seconds
@Transactional
public void releaseExpiredHolds() {
    Instant now = Instant.now();
    
    // Find all expired holds
    List<Seat> expiredSeats = seatRepository
        .findByStatusAndHoldExpiresAtBefore(SeatStatus.HELD, now);
    
    if (expiredSeats.isEmpty()) return;
    
    // Group by event for efficiency
    Map<String, List<Seat>> seatsByEvent = expiredSeats.stream()
        .collect(Collectors.groupingBy(seat -> seat.getEvent().getId()));
    
    for (Map.Entry<String, List<Seat>> entry : seatsByEvent.entrySet()) {
        // Release seats
        for (Seat seat : entry.getValue()) {
            seat.setStatus(SeatStatus.AVAILABLE);
            seat.setHeldByUserId(null);
            seat.setHoldExpiresAt(null);
        }
        seatRepository.saveAll(entry.getValue());
        
        // Increment available seats counter
        Event event = eventRepository.findById(entry.getKey()).orElseThrow();
        event.setAvailableSeats(event.getAvailableSeats() + entry.getValue().size());
        eventRepository.save(event);
        
        // Expire bookings
        List<Booking> bookings = bookingRepository
            .findByEventIdAndStatusAndExpiresAtBefore(entry.getKey(), PENDING, now);
        bookings.forEach(b -> b.setStatus(BookingStatus.EXPIRED));
        bookingRepository.saveAll(bookings);
    }
}
```

## Waiting Room/Queue

**WaitingRoomService.joinQueue():**
```java
public WaitingRoomEntry joinQueue(String eventId, String userId, String sessionId) {
    String queueKey = "queue:" + eventId;
    long timestamp = System.currentTimeMillis();
    
    // Add to Redis sorted set (score = timestamp for FIFO)
    redisTemplate.opsForZSet().add(queueKey, sessionId, timestamp);
    
    // Get queue position
    Long position = redisTemplate.opsForZSet().rank(queueKey, sessionId);
    
    // Save to DB
    WaitingRoomEntry entry = WaitingRoomEntry.builder()
        .eventId(eventId)
        .sessionId(sessionId)
        .queuePosition(position.intValue() + 1)
        .status(QueueStatus.WAITING)
        .build();
    
    return waitingRoomRepository.save(entry);
}
```

**Admitting Users (Scheduled):**
```java
@Scheduled(fixedRate = 10000)  // Every 10 seconds
@Transactional
public void admitUsers() {
    List<Event> events = eventRepository
        .findByRequiresWaitingRoomTrueAndStatus(EventStatus.ON_SALE);
    
    for (Event event : events) {
        // Admit top 100 from queue
        Set<String> admitted = redisTemplate.opsForZSet()
            .range("queue:" + event.getId(), 0, 99);
        
        for (String sessionId : admitted) {
            // Generate 30-min access token
            String token = generateAccessToken(sessionId, event.getId());
            redisTemplate.opsForValue().set(
                "access:" + sessionId, token, Duration.ofMinutes(30)
            );
            
            // Update DB status
            WaitingRoomEntry entry = waitingRoomRepository
                .findByEventIdAndSessionId(event.getId(), sessionId);
            entry.setStatus(QueueStatus.ALLOWED);
            waitingRoomRepository.save(entry);
        }
        
        // Remove from queue
        redisTemplate.opsForZSet().removeRange("queue:" + event.getId(), 0, 99);
    }
}
```

## Key Takeaways

1. **SERIALIZABLE + Pessimistic locks**: Guarantees no double-booking
2. **Distributed Redis lock**: Coordinates across servers
3. **Idempotent payment**: Uses booking ID as key
4. **Scheduled expiry**: Runs every 30 sec to release holds
5. **Waiting room**: Redis sorted set for FIFO queue

**How to run:**
```bash
# Start PostgreSQL & Redis
docker run -d -p 5432:5432 -e POSTGRES_DB=ticketmaster postgres:15
docker run -d -p 6379:6379 redis:7

# Run app
cd ticketmaster
mvn spring-boot:run

# Test API
curl http://localhost:8080/api/v1/events?city=New%20York
```
