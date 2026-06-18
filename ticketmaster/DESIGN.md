# Ticketmaster Event Ticketing System — System Design

> **Implementation status:** This repository contains a complete, production-quality Spring Boot
> implementation of the design described here. Every architectural decision maps directly to
> code in `src/main/java/com/systemdesign/ticketmaster/`.

---

## Table of Contents

1. [Problem Statement & Requirements](#1-problem-statement--requirements)
2. [Capacity Estimation](#2-capacity-estimation)
3. [Core Entities & Data Model](#3-core-entities--data-model)
4. [API Design](#4-api-design)
5. [High-Level Architecture](#5-high-level-architecture)
6. [Deep Dive: Seat Hold & Reservation](#6-deep-dive-seat-hold--reservation)
7. [Deep Dive: Concurrency Control](#7-deep-dive-concurrency-control)
8. [Deep Dive: Payment Processing](#8-deep-dive-payment-processing)
9. [Deep Dive: Waiting Room & Queue](#9-deep-dive-waiting-room--queue)
10. [Deep Dive: Scalability](#10-deep-dive-scalability)
11. [Deep Dive: Availability](#11-deep-dive-availability)
12. [Trade-offs & Alternatives](#12-trade-offs--alternatives)

---

## 1. Problem Statement & Requirements

### Functional Requirements

| # | Requirement |
|---|-------------|
| FR-1 | Users can browse events by location, date, category |
| FR-2 | Users can view available seats for an event in real-time |
| FR-3 | Users can select and temporarily hold seats (5-10 minute hold) |
| FR-4 | Users can complete payment to confirm booking |
| FR-5 | System prevents double-booking (two users cannot book the same seat) |
| FR-6 | Expired holds automatically release seats back to inventory |
| FR-7 | Waiting room/queue for high-demand events (Taylor Swift, World Cup) |

### Non-Functional Requirements

| Metric | Target |
|--------|--------|
| Scale | 10 million events/year, 100 million bookings/year |
| Concurrent users | 500K users during ticket sale start |
| Booking latency (p95) | < 2 seconds (hold seat) |
| No double-booking | 100% accuracy (strong consistency) |
| Availability | 99.99% (≤ 52 minutes downtime/year) |
| Payment timeout | 10 minutes max |
| Hold expiry | 5-10 minutes configurable per event |

### Out of Scope

- Secondary ticket marketplace (resale)
- Dynamic pricing / surge pricing
- Seat recommendations ("best available")
- Mobile app push notifications
- Social features (invite friends, group bookings)

---

## 2. Capacity Estimation

### Storage Estimation

**Events:**
- 10M events/year
- Average event data: 2 KB (name, venue, date, description)
- Annual storage: 10M × 2 KB = **20 GB/year**
- 5-year retention: **100 GB**

**Tickets (Seats):**
- Average venue capacity: 5,000 seats
- 10M events × 5,000 seats = 50 billion seats total
- Seat record: 200 bytes (event_id, seat_number, row, section, price, status)
- Total: 50B × 200 bytes = **10 TB**
- With indices & replication (3×): **30 TB**

**Bookings:**
- 100M bookings/year
- Booking record: 500 bytes (user_id, event_id, seats, payment, timestamp)
- Annual storage: 100M × 500 bytes = **50 GB/year**
- 5-year retention: **250 GB**

**Total storage:** ~**30.4 TB** (primarily seats)

### Traffic Estimation

**Normal Load:**
- 100M bookings/year ÷ 365 days ÷ 86,400 seconds = **~3 bookings/second**
- Browse events: 10× bookings = **~30 QPS**

**Peak Load (High-Demand Event):**
- Taylor Swift concert: 50K capacity, 500K concurrent users trying to book
- Sale starts at 10 AM: Spike to **100K requests/second** in first minute
- Gradually decreases over 30 minutes until sold out

**Seat Availability Checks:**
- User checks seat map: **~1,000 QPS** normal, **~50K QPS** peak

### Bandwidth Estimation

**Booking request:**
- Request: 1 KB (event_id, seat_ids, user_id)
- Response: 2 KB (booking confirmation, payment details)
- Peak: 100K requests/s × 3 KB = **300 MB/s** = **2.4 Gbps**

**Seat availability response:**
- Seat map: 50 KB (5,000 seats × 10 bytes each)
- Peak: 50K requests/s × 50 KB = **2.5 GB/s** = **20 Gbps**

---

## 3. Core Entities & Data Model

### Event

```java
@Entity
@Table(name = "events", indexes = {
    @Index(name = "idx_venue_date", columnList = "venue_id,event_date"),
    @Index(name = "idx_date", columnList = "event_date"),
    @Index(name = "idx_status", columnList = "status")
})
public class Event {
    @Id
    private String id;
    
    private String name;                  // "Taylor Swift - Eras Tour"
    private String description;
    private String category;              // "Concert", "Sports", "Theater"
    
    @ManyToOne
    private Venue venue;
    
    private Instant eventDate;
    private Instant saleStartDate;        // When tickets go on sale
    
    @Enumerated(EnumType.STRING)
    private EventStatus status;           // UPCOMING, ON_SALE, SOLD_OUT, CANCELLED
    
    private Integer totalSeats;
    private Integer availableSeats;       // Denormalized for quick check
    
    private Integer holdTimeMinutes;      // Configurable hold duration (5-10 min)
    private Boolean requiresWaitingRoom;  // True for high-demand events
}
```

### Venue

```java
@Entity
@Table(name = "venues")
public class Venue {
    @Id
    private String id;
    
    private String name;                  // "Madison Square Garden"
    private String city;
    private String state;
    private String country;
    private String address;
    
    private Integer capacity;
    
    @OneToMany(mappedBy = "venue")
    private List<Section> sections;       // Floor, Balcony, VIP, etc.
}
```

### Section

```java
@Entity
@Table(name = "sections")
public class Section {
    @Id
    private String id;
    
    @ManyToOne
    private Venue venue;
    
    private String name;                  // "Section A", "Floor", "Balcony"
    private Integer rowCount;
    private Integer seatsPerRow;
}
```

### Seat

```java
@Entity
@Table(name = "seats", indexes = {
    @Index(name = "idx_event_status", columnList = "event_id,status"),
    @Index(name = "idx_event_section", columnList = "event_id,section_id")
})
public class Seat {
    @Id
    private String id;
    
    @ManyToOne
    private Event event;
    
    @ManyToOne
    private Section section;
    
    private String seatNumber;            // "A-12"
    private String rowNumber;             // "A"
    
    private BigDecimal price;
    
    @Enumerated(EnumType.STRING)
    private SeatStatus status;            // AVAILABLE, HELD, BOOKED
    
    private String heldByUserId;          // User holding the seat
    private Instant holdExpiresAt;        // When hold expires
    
    @Version
    private Long version;                 // Optimistic locking
}
```

### Booking

```java
@Entity
@Table(name = "bookings", indexes = {
    @Index(name = "idx_user", columnList = "user_id"),
    @Index(name = "idx_event", columnList = "event_id"),
    @Index(name = "idx_status", columnList = "status")
})
public class Booking {
    @Id
    private String id;
    
    private String userId;
    
    @ManyToOne
    private Event event;
    
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    private List<BookedSeat> seats;
    
    private BigDecimal totalAmount;
    
    @Enumerated(EnumType.STRING)
    private BookingStatus status;         // PENDING, CONFIRMED, CANCELLED, EXPIRED
    
    private String paymentId;             // Stripe/PayPal transaction ID
    private String paymentMethod;         // CREDIT_CARD, PAYPAL, etc.
    
    private Instant createdAt;
    private Instant confirmedAt;
    private Instant expiresAt;            // Payment deadline
}
```

### BookedSeat

```java
@Entity
@Table(name = "booked_seats")
public class BookedSeat {
    @Id
    private String id;
    
    @ManyToOne
    private Booking booking;
    
    @ManyToOne
    private Seat seat;
    
    private BigDecimal price;             // Price at time of booking
}
```

### WaitingRoomEntry

```java
@Entity
@Table(name = "waiting_room_entries", indexes = {
    @Index(name = "idx_event_position", columnList = "event_id,queue_position")
})
public class WaitingRoomEntry {
    @Id
    private String id;
    
    @ManyToOne
    private Event event;
    
    private String userId;
    private String sessionId;
    
    private Integer queuePosition;        // Position in queue
    private Instant joinedAt;
    private Instant allowedAt;            // When user can enter site
    
    @Enumerated(EnumType.STRING)
    private QueueStatus status;           // WAITING, ALLOWED, EXPIRED
}
```

---

## 4. API Design

### 4.1 Browse Events

**Request:**
```http
GET /api/v1/events?city=New York&date=2026-07-01&category=Concert&page=0&size=20
```

**Response:**
```json
{
  "events": [
    {
      "id": "evt_123",
      "name": "Taylor Swift - Eras Tour",
      "venue": {
        "id": "venue_456",
        "name": "Madison Square Garden",
        "city": "New York"
      },
      "eventDate": "2026-07-15T20:00:00Z",
      "saleStartDate": "2026-06-01T10:00:00Z",
      "status": "ON_SALE",
      "availableSeats": 1234,
      "totalSeats": 20000,
      "priceRange": {
        "min": 89.50,
        "max": 499.00
      }
    }
  ],
  "page": 0,
  "totalPages": 5
}
```

### 4.2 View Seat Availability

**Request:**
```http
GET /api/v1/events/{eventId}/seats?section=Floor
```

**Response:**
```json
{
  "eventId": "evt_123",
  "sections": [
    {
      "sectionId": "sec_A",
      "sectionName": "Floor",
      "rows": [
        {
          "rowNumber": "A",
          "seats": [
            {
              "seatId": "seat_1",
              "seatNumber": "A-1",
              "price": 249.00,
              "status": "AVAILABLE"
            },
            {
              "seatId": "seat_2",
              "seatNumber": "A-2",
              "price": 249.00,
              "status": "HELD"
            },
            {
              "seatId": "seat_3",
              "seatNumber": "A-3",
              "price": 249.00,
              "status": "BOOKED"
            }
          ]
        }
      ]
    }
  ]
}
```

### 4.3 Hold Seats

**Request:**
```http
POST /api/v1/bookings/hold
Content-Type: application/json

{
  "eventId": "evt_123",
  "seatIds": ["seat_1", "seat_4", "seat_5"],
  "userId": "user_789"
}
```

**Response:**
```json
{
  "bookingId": "booking_999",
  "status": "PENDING",
  "seats": [
    {
      "seatId": "seat_1",
      "seatNumber": "A-1",
      "price": 249.00
    }
  ],
  "totalAmount": 747.00,
  "expiresAt": "2026-06-18T10:15:00Z",
  "holdDurationSeconds": 600
}
```

**Error response (seat already taken):**
```json
{
  "error": "SEATS_UNAVAILABLE",
  "message": "One or more seats are no longer available",
  "unavailableSeats": ["seat_4"]
}
```

### 4.4 Confirm Booking (Payment)

**Request:**
```http
POST /api/v1/bookings/{bookingId}/confirm
Content-Type: application/json

{
  "paymentMethod": "CREDIT_CARD",
  "paymentToken": "tok_visa_4242",
  "amount": 747.00
}
```

**Response:**
```json
{
  "bookingId": "booking_999",
  "status": "CONFIRMED",
  "paymentId": "pay_abc123",
  "confirmedAt": "2026-06-18T10:12:30Z",
  "tickets": [
    {
      "ticketId": "tkt_001",
      "seatNumber": "A-1",
      "qrCode": "https://cdn.example.com/qr/tkt_001.png"
    }
  ]
}
```

### 4.5 Join Waiting Room

**Request:**
```http
POST /api/v1/events/{eventId}/waiting-room/join
Content-Type: application/json

{
  "userId": "user_789",
  "sessionId": "sess_abc"
}
```

**Response:**
```json
{
  "entryId": "entry_123",
  "queuePosition": 12567,
  "estimatedWaitMinutes": 15,
  "status": "WAITING"
}
```

### 4.6 Check Queue Status

**Request:**
```http
GET /api/v1/events/{eventId}/waiting-room/status?sessionId=sess_abc
```

**Response:**
```json
{
  "status": "ALLOWED",
  "allowedAt": "2026-06-18T10:15:00Z",
  "accessToken": "access_xyz",
  "expiresIn": 1800
}
```

---

## 5. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        Client Apps                                │
│                   (Web, iOS, Android)                             │
└────────────────┬─────────────────────────────────────────────────┘
                 │
                 ▼
         ┌───────────────┐
         │  CDN + WAF    │
         │ (CloudFlare)  │
         └───────┬───────┘
                 │
                 ▼
    ┌────────────────────────────┐
    │   Load Balancer (ALB)      │
    └────────────┬───────────────┘
                 │
    ┌────────────┴──────────────┐
    │                           │
    ▼                           ▼
┌─────────────┐         ┌─────────────┐
│Waiting Room │         │ Booking API │  (Auto-scaling)
│  Service    │   ...   │  Service    │
└──┬──────────┘         └──┬──────┬───┘
   │                       │      │
   │                       │      │
   ▼                       ▼      ▼
┌──────────┐         ┌──────────┐  ┌──────────┐
│  Redis   │         │  Redis   │  │  Redis   │  (Queue + Cache)
│  Queue   │         │  Cache   │  │  Lock    │
└──────────┘         └──────────┘  └──────────┘
   │                       │
   └───────────────┬───────┘
                   │
                   ▼
         ┌──────────────────────┐
         │   PostgreSQL         │
         │   (Primary)          │
         │  SERIALIZABLE        │
         └──────┬───────────────┘
                │
         ┌──────┴───────┐
         │              │
         ▼              ▼
    ┌────────┐     ┌────────┐
    │Replica │     │Replica │  (Read-only)
    └────────┘     └────────┘

┌─────────────────────────────────────────────────────────────────┐
│                     Background Services                          │
└─────────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────┐         ┌──────────────────┐
│  Hold Expiry     │────────▶│  PostgreSQL      │
│  Service         │         │  (Release seats) │
└──────────────────┘         └──────────────────┘
         │
         │
┌──────────────────┐         ┌──────────────────┐
│  Payment         │────────▶│  Stripe API      │
│  Service         │         │  (External)      │
└──────────────────┘         └──────────────────┘
```

### Key Components

1. **Waiting Room Service:** Rate-limits access during high-demand events
2. **Booking API Service:** Handles seat holds, reservations, payment
3. **Redis Queue:** FIFO queue for waiting room
4. **Redis Cache:** Cached seat availability, event details
5. **Redis Lock:** Distributed locks for seat hold operations
6. **PostgreSQL (SERIALIZABLE):** Strong consistency for bookings
7. **Hold Expiry Service:** Background job to release expired holds
8. **Payment Service:** Integrates with Stripe/PayPal

---

## 6. Deep Dive: Seat Hold & Reservation

### 6.1 Hold Flow

**User selects seats → System holds them for 10 minutes → User pays → Booking confirmed**

```java
@Service
@Transactional(isolation = Isolation.SERIALIZABLE)
public class BookingService {
    
    public BookingResponse holdSeats(HoldRequest request) {
        // 1. Validate event
        Event event = eventRepository.findById(request.getEventId())
            .orElseThrow(() -> new EventNotFoundException());
        
        if (event.getStatus() != EventStatus.ON_SALE) {
            throw new EventNotAvailableException();
        }
        
        // 2. Acquire distributed lock
        String lockKey = "booking:event:" + event.getId();
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                throw new SystemOverloadException("Please try again");
            }
            
            // 3. Fetch seats with pessimistic lock
            List<Seat> seats = seatRepository.findByIdInWithLock(request.getSeatIds());
            
            // 4. Validate all seats available
            for (Seat seat : seats) {
                if (seat.getStatus() != SeatStatus.AVAILABLE) {
                    throw new SeatsUnavailableException(seat.getId());
                }
            }
            
            // 5. Hold seats
            Instant holdExpiry = Instant.now().plus(
                event.getHoldTimeMinutes(), ChronoUnit.MINUTES
            );
            
            for (Seat seat : seats) {
                seat.setStatus(SeatStatus.HELD);
                seat.setHeldByUserId(request.getUserId());
                seat.setHoldExpiresAt(holdExpiry);
            }
            seatRepository.saveAll(seats);
            
            // 6. Create booking
            Booking booking = Booking.builder()
                .id(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .event(event)
                .status(BookingStatus.PENDING)
                .totalAmount(calculateTotal(seats))
                .expiresAt(holdExpiry)
                .build();
            
            bookingRepository.save(booking);
            
            // 7. Decrement available seats (denormalized counter)
            event.setAvailableSeats(event.getAvailableSeats() - seats.size());
            eventRepository.save(event);
            
            // 8. Invalidate cache
            cacheService.evict("seats:" + event.getId());
            
            return buildResponse(booking);
            
        } finally {
            lock.unlock();
        }
    }
}
```

**Why `SERIALIZABLE` isolation?**
- Prevents phantom reads: Two transactions can't both see the same seat as available
- Guarantees no double-booking

**Why distributed lock?**
- Prevents concurrent transactions on the same event
- Redis lock is faster than DB lock for high contention

### 6.2 Hold Expiry

**Background job runs every 30 seconds:**

```java
@Scheduled(fixedRate = 30000)
@Transactional
public void releaseExpiredHolds() {
    Instant now = Instant.now();
    
    // Find all expired holds
    List<Seat> expiredSeats = seatRepository
        .findByStatusAndHoldExpiresAtBefore(SeatStatus.HELD, now);
    
    if (expiredSeats.isEmpty()) {
        return;
    }
    
    log.info("Releasing {} expired seat holds", expiredSeats.size());
    
    // Group by event for efficient updates
    Map<String, List<Seat>> seatsByEvent = expiredSeats.stream()
        .collect(Collectors.groupingBy(seat -> seat.getEvent().getId()));
    
    for (Map.Entry<String, List<Seat>> entry : seatsByEvent.entrySet()) {
        String eventId = entry.getKey();
        List<Seat> seats = entry.getValue();
        
        // Release seats
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.AVAILABLE);
            seat.setHeldByUserId(null);
            seat.setHoldExpiresAt(null);
        }
        seatRepository.saveAll(seats);
        
        // Increment available seats
        Event event = eventRepository.findById(eventId).orElseThrow();
        event.setAvailableSeats(event.getAvailableSeats() + seats.size());
        eventRepository.save(event);
        
        // Expire associated bookings
        List<Booking> bookings = bookingRepository
            .findByEventIdAndStatusAndExpiresAtBefore(
                eventId, BookingStatus.PENDING, now
            );
        
        for (Booking booking : bookings) {
            booking.setStatus(BookingStatus.EXPIRED);
        }
        bookingRepository.saveAll(bookings);
        
        // Invalidate cache
        cacheService.evict("seats:" + eventId);
    }
}
```

**Alternative: TTL in Redis**
- Store holds in Redis with TTL
- On expiry, Redis pub/sub triggers release
- Faster, but requires dual-write (DB + Redis)

---

## 7. Deep Dive: Concurrency Control

### 7.1 The Double-Booking Problem

**Scenario:**
- User A selects Seat 12-A at 10:00:00.000
- User B selects Seat 12-A at 10:00:00.001
- Both see seat as available
- Both attempt to book → **CONFLICT**

### 7.2 Solution 1: Pessimistic Locking (Chosen)

```java
@Repository
public interface SeatRepository extends JpaRepository<Seat, String> {
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id IN :ids")
    List<Seat> findByIdInWithLock(@Param("ids") List<String> ids);
}
```

**How it works:**
- `SELECT ... FOR UPDATE` locks rows
- User A's transaction locks Seat 12-A
- User B's transaction **waits** until A commits/rolls back
- Only one transaction succeeds

**Trade-off:**
- ✅ Simple, guaranteed correctness
- ❌ Locks reduce throughput (serializes access)

### 7.3 Solution 2: Optimistic Locking (Alternative)

```java
@Entity
public class Seat {
    @Version
    private Long version;  // JPA increments on every update
}

@Transactional
public void holdSeats(List<String> seatIds) {
    List<Seat> seats = seatRepository.findByIdIn(seatIds);
    
    for (Seat seat : seats) {
        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new SeatsUnavailableException();
        }
        seat.setStatus(SeatStatus.HELD);
    }
    
    try {
        seatRepository.saveAll(seats);  // JPA checks version
    } catch (OptimisticLockingFailureException e) {
        // Another transaction modified the seat → retry
        throw new SeatsUnavailableException();
    }
}
```

**How it works:**
- No locks during read
- On write, JPA checks `version` hasn't changed
- If version changed → another transaction won → throw exception

**Trade-off:**
- ✅ Higher throughput (no blocking)
- ❌ Requires retry logic on failure
- ❌ More failures under high contention

**Why pessimistic locking?**
- Booking is high-value, high-contention
- Better to wait 100ms than retry 10 times
- Pessimistic lock guarantees exactly one winner

### 7.4 Distributed Locking with Redis

```java
@Service
public class BookingService {
    
    private final RedissonClient redissonClient;
    
    public BookingResponse holdSeats(HoldRequest request) {
        String lockKey = "booking:event:" + request.getEventId();
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            // Try to acquire lock, wait max 5s, auto-release after 10s
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                throw new SystemOverloadException();
            }
            
            // Critical section: hold seats
            return doHoldSeats(request);
            
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

**Why event-level locking?**
- Prevents concurrent bookings for the same event
- Coarse-grained lock → simpler than per-seat locking
- Works across multiple API servers (distributed)

**Trade-off:**
- ✅ Prevents conflicts across servers
- ❌ Serializes all bookings for an event (limits throughput)
- Better approach: Shard by section (lock per section)

---

## 8. Deep Dive: Payment Processing

### 8.1 Payment Flow

```
User confirms booking
  → Hold seats (PENDING booking created)
  → Charge payment (call Stripe API)
  → If success: Mark booking CONFIRMED, seats BOOKED
  → If failure: Release seats, mark booking FAILED
```

### 8.2 Implementation

```java
@Service
public class PaymentService {
    
    private final StripeClient stripeClient;
    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    
    @Transactional
    public BookingResponse confirmBooking(String bookingId, PaymentRequest payment) {
        // 1. Fetch booking
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException());
        
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new InvalidBookingStateException();
        }
        
        if (Instant.now().isAfter(booking.getExpiresAt())) {
            throw new BookingExpiredException();
        }
        
        // 2. Call payment gateway (Stripe)
        try {
            PaymentIntent paymentIntent = stripeClient.charge(
                booking.getTotalAmount(),
                payment.getPaymentToken(),
                booking.getId()  // idempotency key
            );
            
            // 3. Payment succeeded → confirm booking
            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setPaymentId(paymentIntent.getId());
            booking.setPaymentMethod(payment.getPaymentMethod());
            booking.setConfirmedAt(Instant.now());
            bookingRepository.save(booking);
            
            // 4. Mark seats as BOOKED
            List<Seat> seats = seatRepository.findByBookingId(bookingId);
            for (Seat seat : seats) {
                seat.setStatus(SeatStatus.BOOKED);
                seat.setHeldByUserId(null);  // No longer held
                seat.setHoldExpiresAt(null);
            }
            seatRepository.saveAll(seats);
            
            return buildResponse(booking);
            
        } catch (StripeException e) {
            log.error("Payment failed for booking {}: {}", bookingId, e.getMessage());
            
            // Payment failed → release seats
            releaseSeats(booking);
            
            booking.setStatus(BookingStatus.FAILED);
            bookingRepository.save(booking);
            
            throw new PaymentFailedException(e.getMessage());
        }
    }
    
    private void releaseSeats(Booking booking) {
        List<Seat> seats = seatRepository.findByBookingId(booking.getId());
        for (Seat seat : seats) {
            seat.setStatus(SeatStatus.AVAILABLE);
            seat.setHeldByUserId(null);
            seat.setHoldExpiresAt(null);
        }
        seatRepository.saveAll(seats);
        
        Event event = booking.getEvent();
        event.setAvailableSeats(event.getAvailableSeats() + seats.size());
        eventRepository.save(event);
    }
}
```

### 8.3 Idempotency

**Problem:** Network timeout → user retries → double charge?

**Solution:** Idempotency key

```java
PaymentIntent intent = stripeClient.charge(
    amount,
    token,
    bookingId  // Idempotency key
);
```

**How it works:**
- Stripe stores idempotency key + response
- If same key sent twice → Stripe returns cached response (no double charge)
- Key must be unique per booking

### 8.4 Failure Handling

**Scenario 1: Payment succeeds, DB write fails**
- User charged, but booking not confirmed
- Solution: Retry DB write (payment already idempotent)
- Worst case: Manual reconciliation (refund user)

**Scenario 2: Payment fails, but seats held**
- Already handled: `releaseSeats()` in catch block

**Scenario 3: Payment pending (3D Secure redirect)**
- Mark booking as `PAYMENT_PENDING`
- Stripe webhook notifies on completion
- Update booking status asynchronously

---

## 9. Deep Dive: Waiting Room & Queue

### 9.1 Why Waiting Room?

**Problem:** 500K users hit site at 10 AM when Taylor Swift tickets go on sale → **500K concurrent requests** → site crashes

**Solution:** Waiting room queues users, admits them gradually

### 9.2 Queue Implementation

```java
@Service
public class WaitingRoomService {
    
    private final RedisTemplate<String, String> redisTemplate;
    
    public WaitingRoomEntry joinQueue(String eventId, String userId, String sessionId) {
        String queueKey = "queue:" + eventId;
        
        // Add user to sorted set (score = timestamp)
        long timestamp = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(queueKey, sessionId, timestamp);
        
        // Get queue position
        Long position = redisTemplate.opsForZSet().rank(queueKey, sessionId);
        
        WaitingRoomEntry entry = WaitingRoomEntry.builder()
            .id(UUID.randomUUID().toString())
            .eventId(eventId)
            .userId(userId)
            .sessionId(sessionId)
            .queuePosition(position != null ? position.intValue() : 0)
            .joinedAt(Instant.now())
            .status(QueueStatus.WAITING)
            .build();
        
        waitingRoomRepository.save(entry);
        
        return entry;
    }
    
    @Scheduled(fixedRate = 10000)  // Every 10 seconds
    public void admitUsers() {
        List<Event> highDemandEvents = eventRepository
            .findByRequiresWaitingRoomTrueAndStatus(EventStatus.ON_SALE);
        
        for (Event event : highDemandEvents) {
            String queueKey = "queue:" + event.getId();
            
            // Admit 100 users per batch
            Set<String> admitted = redisTemplate.opsForZSet()
                .range(queueKey, 0, 99);
            
            if (admitted.isEmpty()) continue;
            
            for (String sessionId : admitted) {
                // Generate access token
                String token = generateAccessToken(sessionId, event.getId());
                
                // Store token in Redis (30-min TTL)
                redisTemplate.opsForValue().set(
                    "access:" + sessionId, 
                    token, 
                    30, 
                    TimeUnit.MINUTES
                );
                
                // Update DB
                WaitingRoomEntry entry = waitingRoomRepository
                    .findByEventIdAndSessionId(event.getId(), sessionId);
                if (entry != null) {
                    entry.setStatus(QueueStatus.ALLOWED);
                    entry.setAllowedAt(Instant.now());
                    waitingRoomRepository.save(entry);
                }
            }
            
            // Remove admitted users from queue
            redisTemplate.opsForZSet().removeRange(queueKey, 0, 99);
            
            log.info("Admitted {} users for event {}", admitted.size(), event.getId());
        }
    }
}
```

### 9.3 Access Control

```java
@Component
public class WaitingRoomInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, ...) {
        String eventId = extractEventId(request);
        Event event = eventRepository.findById(eventId).orElse(null);
        
        if (event == null || !event.getRequiresWaitingRoom()) {
            return true;  // No waiting room required
        }
        
        String sessionId = request.getSession().getId();
        String token = redisTemplate.opsForValue().get("access:" + sessionId);
        
        if (token == null) {
            // User not admitted → redirect to waiting room
            response.sendRedirect("/waiting-room?eventId=" + eventId);
            return false;
        }
        
        return true;  // User has access token → proceed
    }
}
```

### 9.4 Fairness

**FIFO (First In, First Out):**
- Redis Sorted Set with timestamp as score
- Fair: Users admitted in order they joined

**Randomized:**
- Random score instead of timestamp
- Prevents bots from gaming the queue

**Weighted:**
- VIP users get lower score (higher priority)
- Fan club members prioritized

---

## 10. Deep Dive: Scalability

### 10.1 Horizontal Scaling

**API Tier:**
- Stateless services → easy horizontal scaling
- Auto-scaling based on queue depth
- Target: 70% CPU utilization

```yaml
# Kubernetes HPA
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef:
    name: ticketmaster-api
  minReplicas: 10
  maxReplicas: 200
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

### 10.2 Database Scaling

**Vertical Scaling (First Step):**
- Upgrade to larger instance (more CPU, RAM)
- PostgreSQL can handle 10K TPS on modern hardware

**Read Replicas:**
- Route read queries (event browse, seat view) to replicas
- Write queries (hold, book) → primary

**Sharding (If Needed):**
- Shard by `event_id` or `venue_id`
- Each shard handles a subset of events
- Booking query: `SELECT * FROM seats WHERE event_id = 'evt_123'` → hits one shard

**Connection Pooling:**
- HikariCP with 100-200 connections per API server
- Prevents connection exhaustion

### 10.3 Redis Scaling

**Redis Cluster:**
- 6-node cluster (3 primary, 3 replicas)
- Distribute locks, queues, cache across nodes

**Separate Redis Instances:**
- Redis 1: Waiting room queue (sorted sets)
- Redis 2: Distributed locks (low-latency critical)
- Redis 3: Cache (seat availability, events)

**Why separate?**
- Isolate failure domains
- Tune each for its workload (queue vs cache vs locks)

---

## 11. Deep Dive: Availability

### 11.1 Database Replication

```
Primary (RW) ──┐
               ├──▶ Replica 1 (RO) ── Browse queries
               ├──▶ Replica 2 (RO) ── Seat availability
               └──▶ Replica 3 (RO) ── Analytics
```

**Failover:**
- If primary fails → promote replica to primary (1-2 min downtime)
- Use PostgreSQL streaming replication + PgPool or Patroni

### 11.2 Circuit Breaker

```java
@CircuitBreaker(name = "payment", fallbackMethod = "paymentFallback")
public PaymentResponse processPayment(PaymentRequest request) {
    return stripeClient.charge(request);
}

private PaymentResponse paymentFallback(PaymentRequest request, Exception e) {
    log.error("Payment service down: {}", e.getMessage());
    // Queue payment for retry
    paymentQueue.add(request);
    return PaymentResponse.pending();
}
```

### 11.3 Rate Limiting

**Per-User Rate Limiting:**
```java
@RateLimiter(name = "booking", limitForPeriod = 10, limitRefreshPeriod = 1)
public BookingResponse holdSeats(HoldRequest request) {
    // Max 10 booking attempts per minute per user
}
```

**Global Rate Limiting:**
- Waiting room ensures max 10K concurrent users on booking pages
- API gateway limits to 100K requests/sec globally

---

## 12. Trade-offs & Alternatives

### 12.1 Locking Strategy

**Pessimistic Locking (Chosen):**
- ✅ Guaranteed correctness
- ✅ No retry logic needed
- ❌ Serializes access (lower throughput)

**Optimistic Locking (Alternative):**
- ✅ Higher throughput
- ❌ High failure rate under contention
- ❌ Requires retry logic

**When to use optimistic?**
- Low-contention scenarios (e.g., booking for unpopular events)
- Read-heavy workloads

### 12.2 Hold Expiry

**Background Job (Chosen):**
- Runs every 30 seconds
- ✅ Simple, centralized
- ❌ Delay up to 30 seconds

**Redis TTL (Alternative):**
- Set TTL on hold key in Redis
- On expiry, Redis pub/sub triggers release
- ✅ Instant expiry
- ❌ Dual-write (DB + Redis)
- ❌ Redis failure → holds never expire

### 12.3 Waiting Room

**Server-Side Queue (Chosen):**
- Redis sorted set
- ✅ Fair (FIFO)
- ✅ Centralized control

**Client-Side Polling (Alternative):**
- Client polls `/queue/status` every 10 seconds
- ✅ No server state
- ❌ Polling overhead
- ❌ Harder to enforce fairness

**WebSocket (Alternative):**
- Server pushes queue updates to client
- ✅ Real-time updates
- ❌ Stateful connections (hard to scale)

### 12.4 Payment Processing

**Synchronous (Chosen):**
- Call Stripe API during booking
- ✅ Immediate feedback
- ❌ High latency (Stripe call = 500ms)

**Asynchronous (Alternative):**
- Queue payment, process in background
- ✅ Faster booking response
- ❌ User doesn't know if payment succeeded immediately
- ❌ Complex failure handling

**When to use async?**
- Low-value transactions (< $10)
- User can tolerate delay

### 12.5 Database Choice

**PostgreSQL (Chosen):**
- ✅ SERIALIZABLE isolation
- ✅ Strong consistency
- ✅ ACID guarantees
- ❌ Vertical scaling limit

**DynamoDB (Alternative):**
- ✅ Horizontal scaling
- ❌ No SERIALIZABLE isolation
- ❌ Requires application-level locking

**When to use DynamoDB?**
- Massive scale (1M+ bookings/sec)
- Can tolerate eventual consistency

---

## Summary

This Ticketmaster system design demonstrates:

1. **Strong consistency:** `SERIALIZABLE` isolation + pessimistic locking prevents double-booking
2. **Concurrency control:** Distributed Redis locks across API servers
3. **Payment processing:** Stripe integration with idempotency
4. **Waiting room:** Redis sorted set queue with FIFO admission
5. **Scalability:** Horizontal API scaling, DB replication, Redis cluster
6. **Availability:** Circuit breakers, rate limiting, graceful degradation

**Key Metrics:**
- 500K concurrent users during sale start
- 100K requests/sec peak
- 99.99% availability
- 0% double-booking rate

**Implementation:** Complete Spring Boot codebase in `src/main/java/com/systemdesign/ticketmaster/`.
