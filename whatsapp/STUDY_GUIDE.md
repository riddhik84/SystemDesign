# WhatsApp System Design - Interview Study Guide

## Problem Overview
**Question:** Design a WhatsApp-like messaging system supporting real-time chat, group conversations, offline message delivery, and media attachments.

**Difficulty:** Senior Level (L5-L6)
**Duration:** 45-60 minutes

## Key Requirements (5 minutes)

### Functional
1. **Real-time Messaging** - Send/receive text messages bidirectionally
2. **Group Chats** - Up to 100 participants per group
3. **Offline Delivery** - Store messages for 30 days
4. **Media Attachments** - Images, videos, audio, documents
5. **Multi-Device** - Up to 3 devices per user

### Non-Functional
1. **Scale**: 200M concurrent connections (from 1B users)
2. **Throughput**: 40K messages/second
3. **Latency**: <500ms message delivery
4. **Availability**: Handle component failures
5. **Retention**: 30-day TTL on messages

## Capacity Estimation (5 minutes)

```
Users: 1B total, 200M concurrent (20% online)
Message Volume: 40K messages/second
  = 1B users × 20 messages/day ÷ 86,400 seconds

Storage (per day):
  Messages: 40K × 86,400 × 500 bytes = 1.7 TB/day
  Inbox: 40K × 10 recipients × 200 bytes × 86,400 = 6.9 TB/day

Connections:
  200M connections ÷ 1M per server = 200 Chat Servers

Bandwidth:
  Inbound: 40K × 500 bytes = 20 MB/sec
  Outbound: 40K × 10 recipients × 500 bytes = 200 MB/sec
```

## High-Level Architecture (10 minutes)

```
Client (WebSocket)
  ↓
L4 Load Balancer (TCP)
  ↓
Chat Servers (Connection Handlers)
  ├→ Database (Messages, Inbox, Chats)
  ├→ Redis Pub/Sub (Message Routing)
  └→ S3 (Media Attachments)
```

## Critical Design Decisions (20 minutes)

### 1. WebSocket vs HTTP Polling ⭐⭐⭐

**Options:**
- ❌ HTTP Polling: 1-5 second intervals, high latency, server load
- ❌ HTTP Long Polling: Better latency, but connection overhead
- ✅ WebSocket: Bidirectional, low latency (~50ms), persistent connection

**Chosen: WebSocket**
- Single TCP connection for bidirectional communication
- Server push capability (no client polling needed)
- Low overhead after handshake
- Efficient for high-frequency updates

**Interview Talking Points:**
- "WebSocket provides sub-100ms latency vs. 1-5 seconds with polling"
- "Single connection vs. repeated HTTP requests reduces server load"
- "Bidirectional allows server push and client sends over same connection"

### 2. Message Routing: Redis Pub/Sub ⭐⭐⭐

**Options:**
- ❌ Direct Server-to-Server: Requires knowing which server has user
- ❌ Shared Database Queue: High read load, polling inefficiency
- ✅ Redis Pub/Sub: Lightweight, low-latency broadcast

**Implementation:**
```
Message Flow:
1. Sender → Chat Server A
2. Write to Database + Inbox (durable)
3. Publish to Redis: user:{recipientId}
4. Chat Server B (subscribed) → Deliver via WebSocket
```

**Channel Strategy:**
- Small groups: Per-user channels (user:123)
- Large groups (>25): Per-chat channels (chat:456)
- Adaptive switching based on participant count

**Interview Talking Points:**
- "Redis Pub/Sub is 'at most once' delivery, acceptable with Inbox fallback"
- "Partition channels by userId for horizontal scaling"
- "Decouples Chat Servers - they don't need to know about each other"

### 3. Inbox Pattern for Guaranteed Delivery ⭐⭐⭐

**Problem:** WebSocket unreliable (network failures, server restarts)

**Solution: Inbox Table**
```sql
inbox:
  clientId (separate entry per device)
  messageId
  delivered: Boolean
  expiresAt (30 days)
```

**Delivery Flow:**
```
1. Write message to DB + create Inbox entries (atomic)
2. ACK to sender only after DB write
3. Best-effort delivery via Redis → WebSocket
4. Client ACKs → Delete from Inbox
5. On reconnect → Sync undelivered from Inbox
```

**Why Per-Client Inbox?**
- User has up to 3 devices
- Each device independently tracks delivered messages
- Enables multi-device sync

**Interview Talking Points:**
- "Database write before ACK to sender guarantees durability"
- "Redis is best-effort optimization, Inbox is source of truth"
- "30-day TTL matches business requirement"

### 4. Sequence Numbers for Gap Detection ⭐⭐

**Problem:** Lost messages in Redis Pub/Sub

**Solution:**
```
Each message: monotonic sequence number per sender
Client tracks: lastSeq per sender
Heartbeat: piggybacks sequence numbers every 30 sec
Client detects gap: requests missing messages
```

**Example:**
```
User A sends: seq=1, 2, 3, 4
Client B receives: seq=1, 2, 4 (missed 3)
Heartbeat: lastSeq=4
Client detects gap [3]
Client requests: /api/messages?userId=A&from=3&to=3
```

**Interview Talking Points:**
- "Heartbeat serves dual purpose: liveness + gap detection"
- "Per-sender sequence numbers avoid global coordination"
- "Client-driven recovery reduces server complexity"

## Data Model (5 minutes)

```sql
Chat:
  id, name, type (DIRECT|GROUP), creatorId, createdAt

ChatParticipant (bidirectional lookup):
  chatId, userId (composite PK)
  GSI: userId (find user's chats)
  joinedAt, leftAt, active

Message:
  id, chatId, senderId, content, type
  timestamp, attachmentUrls[], sequenceNumber
  expiresAt (30 days)

Inbox (undelivered queue):
  clientId, messageId (composite PK)
  userId, chatId, timestamp
  delivered, expiresAt

Client (device tracking):
  id, userId, sessionId, deviceType
  online, connectedAt, lastSeenAt
  lastSequenceNumber

LastSeen:
  userId, lastSeenAt, online
```

## Scaling (5 minutes)

**Horizontal Scaling:**
- Chat Servers: 1-2M connections per server
- Redis Pub/Sub: Cluster sharded by userId
- Database: Sharded by userId or chatId

**Connection Density:**
```
200M connections ÷ 1M per server = 200 servers
With 10% headroom = 220 servers
```

**Rate Limiting:**
- 10 messages/second per user
- 100 group participants max
- 10MB attachment size limit

## Interview Talking Track

### Opening (2 min)
"I'll design a WhatsApp-like messaging system for 200M concurrent users, 40K messages/second, with real-time delivery and offline message support..."

### Requirements (5 min)
- Clarify: group size limit? (100)
- Clarify: message retention? (30 days)
- Clarify: devices per user? (3)
- Out of scope: voice/video calls, E2E encryption

### Capacity (5 min)
"For 1B users with 20% online, we have 200M concurrent connections. At 20 messages/day per user, that's ~40K messages/second..."

### High-Level (10 min)
"I'll use WebSocket for real-time connections, Redis Pub/Sub for message routing, and an Inbox table for guaranteed delivery..."

### Deep Dives (20 min)

**Interviewer: "How do you handle offline users?"**
- Inbox table stores undelivered messages
- Write to DB before ACK to sender
- On reconnect, sync from Inbox
- Client ACKs → delete from Inbox
- 30-day TTL for storage management

**Interviewer: "What if Redis Pub/Sub fails?"**
- System degrades gracefully
- Messages still written to Inbox
- On reconnect, client syncs from Inbox
- Fallback polling every 60 seconds
- No message loss, only latency increase

**Interviewer: "How do you detect lost messages?"**
- Sequence numbers per sender
- Heartbeat every 30 seconds with lastSeq
- Client detects gaps in sequence
- Requests missing messages from server
- Eventual consistency acceptable

**Interviewer: "How does group chat scaling work?"**
- Small groups (<25): Per-user Redis channels
- Large groups (>25): Per-chat Redis channel
- Write amplification: N Inbox entries for N participants
- Database sharding by chatId for very large groups

## Common Pitfalls

1. ❌ Not handling offline delivery → Messages lost
2. ❌ Using HTTP polling → High latency, server overload
3. ❌ No sequence numbers → Silent message loss
4. ❌ Single Redis instance → Bottleneck, SPOF
5. ❌ No per-client Inbox → Multi-device sync broken
6. ❌ Forgetting 30-day TTL → Storage runaway
7. ❌ No rate limiting → DDoS vulnerability

## Follow-Up Questions

**"How do you handle message ordering?"**
- NTP-synchronized timestamps at server receipt
- Display by server timestamp (not client time)
- Eventual consistency acceptable
- "Pop-in" behavior for out-of-order messages

**"How do you implement read receipts?"**
- Client sends READ_MESSAGE event
- Server updates MessageStatus table
- Publish to sender's channel
- Sender sees "Read" indicator

**"How do you handle typing indicators?"**
- Direct WebSocket message (not durable)
- 3-second timeout on recipient side
- Not stored in database
- Rate limited to 1/second per user

**"What if a user has 1M followers?"**
- "Celebrity problem"
- Switch to fan-out-on-read for broadcasts
- Pre-compute only for active followers
- Batch delivery over time (acceptable delay)

## Key Takeaways

1. **WebSocket**: Real-time bidirectional communication
2. **Inbox Pattern**: Guaranteed delivery despite failures
3. **Redis Pub/Sub**: Lightweight routing between servers
4. **Sequence Numbers**: Gap detection for reliability
5. **Multi-Device**: Per-client Inbox for sync

## Time Management
- Requirements & Clarifications: 5 min
- Capacity Estimation: 5 min
- High-Level Architecture: 10 min
- Deep Dives (2-3 topics): 20 min
- Trade-offs & Closing: 5 min
