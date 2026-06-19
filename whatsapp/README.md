# WhatsApp Messaging Platform - System Design

A scalable implementation of a WhatsApp-like real-time messaging platform with WebSocket connections, group chats, offline message delivery, and Redis pub/sub. Built with Spring Boot to handle 200M concurrent connections and 40K messages/second.

## 🎯 Overview

This project demonstrates the architecture and implementation of a real-time messaging platform where users can:
- Send and receive real-time text messages via WebSocket
- Create and manage group chats (up to 100 participants)
- Receive offline messages upon reconnection (30-day retention)
- Send and receive media attachments
- Track user online status and last seen

## 📋 System Requirements

### Functional Requirements
- **Group Chat Management**: Create groups with up to 100 participants
- **Real-time Messaging**: Bidirectional messaging with <500ms latency
- **Offline Message Delivery**: Store messages for 30 days for offline users
- **Media Attachments**: Support images, videos, audio, documents
- **Multi-Device Support**: Up to 3 devices per user simultaneously

### Non-Functional Requirements
- **Scale**: 200M concurrent connections (from 1B total users)
- **Throughput**: 40K messages/second
- **Latency**: <500ms message delivery for online users
- **Availability**: Handle component failures gracefully
- **Data Retention**: 30-day TTL on messages and attachments

## 🏗️ Architecture

### High-Level Components

```
┌──────────────┐
│    Client    │
└──────┬───────┘
       │ WebSocket
       v
┌─────────────────────────────────────────────┐
│      L4 Load Balancer (TCP)                 │
└─────────────────────────────────────────────┘
       │
       v
┌─────────────────────────────────────────────┐
│      Chat Servers (WebSocket Handlers)      │
│  ┌────────────────────────────────────┐    │
│  │  In-Memory Connection Map          │    │
│  │  userId → WebSocketSession         │    │
│  └────────────────────────────────────┘    │
└──────────┬──────────────────────────────────┘
           │
    ┌──────┴──────┬──────────────┬────────────┐
    v             v              v            v
┌─────────┐  ┌──────────┐  ┌─────────┐  ┌─────────┐
│Database │  │  Redis   │  │  Redis  │  │   S3    │
│(H2/JPA) │  │ Pub/Sub  │  │  Cache  │  │  Blob   │
│         │  │          │  │         │  │ Storage │
│- Chats  │  │- Message │  │- Conn   │  │- Media  │
│- Msgs   │  │  Routing │  │  State  │  │  Files  │
│- Inbox  │  │- Per User│  │         │  │         │
│- Clients│  │  Channel │  │         │  │         │
└─────────┘  └──────────┘  └─────────┘  └─────────┘
```

### Key Components

1. **L4 Load Balancer**: TCP load balancing for WebSocket connections
2. **Chat Servers**: Handle WebSocket connections, maintain user-to-session mapping
3. **Database (H2/JPA)**: Durable storage for chats, messages, inbox, clients
4. **Redis Pub/Sub**: Lightweight message routing between Chat Servers
5. **Redis Cache**: Connection state and online user tracking
6. **Blob Storage (S3)**: Media attachments with pre-signed URLs

## 🔑 Key Design Decisions

### 1. WebSocket for Real-Time Communication

**Why WebSockets?**
- Bidirectional communication over a single TCP connection
- Low latency (~50ms vs. 200-500ms for HTTP polling)
- Server can push messages to clients
- Efficient for high-frequency updates

**Connection Flow:**
```
1. Client connects: ws://server/ws/chat?userId=user123
2. Server registers in memory: userId → WebSocketSession
3. Server creates Client record in database
4. Server subscribes to Redis channel: user:user123
5. Server syncs offline messages from Inbox table
6. Connection established
```

**Heartbeat Protocol:**
```
Client → HEARTBEAT (every 30 seconds)
Server → HEARTBEAT_RESPONSE + sequenceNumber
```
Purpose: Detect disconnections, piggyback sequence numbers for gap detection

### 2. Redis Pub/Sub for Message Routing

**Architecture:**
```
Message sent → Write to DB + Inbox
            → Publish to Redis: user:{recipientId}
            → Chat Servers subscribed to user channels
            → Deliver via WebSocket if online
```

**Why Redis Pub/Sub?**
- Lightweight, low-latency message distribution
- "At most once" delivery semantics (acceptable with Inbox fallback)
- Scales horizontally (Redis Cluster for large deployments)
- Decouples Chat Servers

**Channel Partitioning:**
- Small groups (<25 users): Per-user channels `user:{userId}`
- Large groups (>25 users): Per-chat channels `chat:{chatId}`
- Adaptive switching based on participant count

### 3. Inbox Table for Offline Delivery

**Purpose:** Durable storage of undelivered messages

**Schema:**
```sql
inbox
  id: UUID
  clientId: UUID (separate entry per device)
  userId: UUID
  messageId: UUID
  chatId: UUID
  timestamp: DateTime
  expiresAt: DateTime (30 days TTL)
  delivered: Boolean
```

**Delivery Guarantees:**
1. Message written to Message table + Inbox entries (atomic transaction)
2. Return success to sender ONLY after DB write
3. Best-effort delivery via Redis Pub/Sub
4. On reconnect: Sync all undelivered messages from Inbox
5. Client ACKs message → Delete from Inbox

**Why Per-Client Inbox?**
- User can have up to 3 devices
- Each device independently tracks delivered messages
- Enables multi-device sync

### 4. Sequence Numbers for Gap Detection

**Implementation:**
```
Each message gets monotonic sequence number per sender
Client tracks lastSequenceNumber per user
Heartbeat piggybacks sequence numbers
Client detects gaps → Requests missing messages
```

**Example:**
```
User A sends messages: seq=1, seq=2, seq=3, seq=4
Client B receives: seq=1, seq=2, seq=4 (missed seq=3)
Heartbeat response: lastSeq=4
Client detects gap [3]
Client requests: GET /api/messages?userId=A&from=3&to=3
```

### 5. Last Seen Optimization

**Strategy:**
```
Online users: In-memory map (userId → online)
Offline users: LastSeen table (userId → timestamp)

getLastSeen(userId):
  if isOnline(userId):
    return "online"
  else:
    return lastSeenTable.get(userId)
```

**Why write only on disconnect?**
- Reduces write amplification (millions of heartbeats → 1 disconnect write)
- Online users respond from memory
- Conditional write prevents race conditions

## 📊 Data Model

### Core Entities

**Chat**
```java
{
  id: UUID,
  name: String,
  type: DIRECT | GROUP,
  creatorId: UUID,
  createdAt: DateTime,
  participants: List<ChatParticipant>
}
```

**ChatParticipant** (bidirectional lookup)
```java
{
  id: UUID,
  chatId: UUID,        // Partition key
  userId: UUID,        // Sort key + GSI
  joinedAt: DateTime,
  leftAt: DateTime,
  active: Boolean
}
```
Indexes: (chatId, userId), (userId) — enables both "get chat participants" and "get user's chats"

**Message**
```java
{
  id: UUID,
  chatId: UUID,
  senderId: UUID,
  content: Text,
  type: TEXT | IMAGE | VIDEO | AUDIO | DOCUMENT,
  timestamp: DateTime,
  attachmentUrls: List<String>,
  sequenceNumber: Long,
  expiresAt: DateTime (30 days)
}
```

**Inbox** (undelivered message queue)
```java
{
  id: UUID,
  clientId: UUID,
  userId: UUID,
  messageId: UUID,
  chatId: UUID,
  timestamp: DateTime,
  expiresAt: DateTime,
  delivered: Boolean
}
```

**Client** (device tracking)
```java
{
  id: UUID,
  userId: UUID,
  sessionId: String,
  deviceType: String,
  deviceId: String,
  online: Boolean,
  connectedAt: DateTime,
  lastSeenAt: DateTime,
  lastSequenceNumber: Long
}
```

## 🔌 API Design

### WebSocket Commands (Client → Server)

```javascript
// Create chat
{
  command: "CREATE_CHAT",
  payload: {
    participants: ["user1", "user2", "user3"],
    name: "Weekend Plans"
  }
}

// Send message
{
  command: "SEND_MESSAGE",
  payload: {
    chatId: "chat-123",
    message: "Hello everyone!",
    messageType: "TEXT",
    attachments: []
  }
}

// Add participant (groups only)
{
  command: "MODIFY_CHAT_PARTICIPANTS",
  payload: {
    chatId: "chat-123",
    userId: "user4",
    operation: "ADD"
  }
}

// Acknowledge message delivery
{
  command: "ACK_MESSAGE",
  payload: {
    messageId: "msg-456",
    chatId: "chat-123"
  }
}

// Sync offline messages
{
  command: "SYNC_INBOX"
}

// Heartbeat (every 30 seconds)
{
  command: "HEARTBEAT",
  sequenceNumber: 12345
}
```

### WebSocket Events (Server → Client)

```javascript
// New message received
{
  command: "NEW_MESSAGE",
  messageId: "msg-456",
  payload: {
    chatId: "chat-123",
    senderId: "user2",
    message: "Hey there!",
    messageType: "TEXT",
    attachments: [],
    timestamp: "2024-01-20T15:30:00",
    sequenceNumber: 42
  }
}

// Chat updated
{
  command: "CHAT_UPDATE",
  payload: {
    chatId: "chat-123",
    participants: ["user1", "user2", "user3", "user4"],
    status: "UPDATED"
  }
}

// Message sent confirmation
{
  command: "MESSAGE_SENT",
  messageId: "msg-789",
  payload: {
    status: "SUCCESS",
    chatId: "chat-123"
  }
}

// Heartbeat response
{
  command: "HEARTBEAT_RESPONSE",
  sequenceNumber: 12345
}
```

### REST API Endpoints

```
POST   /api/chats                    # Create chat
GET    /api/chats/{chatId}           # Get chat details
GET    /api/chats/user/{userId}      # Get user's chats
GET    /api/chats/{chatId}/messages  # Get chat history
POST   /api/chats/{chatId}/participants     # Add participant
DELETE /api/chats/{chatId}/participants/{userId}  # Remove participant
GET    /api/users/{userId}/status    # Get user online status
```

## 🚀 Getting Started

### Prerequisites
- Java 17+
- Maven 3.8+
- Redis (for pub/sub and caching)

### Running Locally

1. **Start Redis**
```bash
docker run -d -p 6379:6379 redis:alpine
```

2. **Build and Run**
```bash
cd whatsapp
mvn clean install
mvn spring-boot:run
```

3. **Connect via WebSocket**
```javascript
const ws = new WebSocket('ws://localhost:8080/ws/chat?userId=user123');

ws.onopen = () => {
  console.log('Connected');
  
  // Send a message
  ws.send(JSON.stringify({
    command: 'SEND_MESSAGE',
    payload: {
      chatId: 'chat-456',
      message: 'Hello!',
      messageType: 'TEXT'
    }
  }));
};

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('Received:', data);
};
```

4. **Test via REST API**
```bash
# Create a chat
curl -X POST http://localhost:8080/api/chats \
  -H "Content-Type: application/json" \
  -d '{
    "participants": ["user1", "user2"],
    "name": "Test Chat",
    "creatorId": "user1"
  }'

# Get user's chats
curl http://localhost:8080/api/chats/user/user1

# Get chat messages
curl http://localhost:8080/api/chats/chat-123/messages?limit=50

# Check user status
curl http://localhost:8080/api/users/user1/status
```

## 📈 Scaling Strategies

### Horizontal Scaling

**Chat Servers:**
```
L4 Load Balancer
├── Chat Server 1 (users 1-5M)
├── Chat Server 2 (users 5M-10M)
├── Chat Server 3 (users 10M-15M)
└── ...
```
- Stateless except for in-memory WebSocket connections
- Consistent hashing via ZooKeeper/Etcd for user assignment
- Connection density: 1-2M users per server

**Redis Pub/Sub:**
```
Redis Cluster
├── Shard 1 (user channels user:1 - user:100M)
├── Shard 2 (user channels user:100M - user:200M)
└── ...
```
- Partition channels by userId hash
- Each Chat Server subscribes to relevant shards

**Database:**
```
PostgreSQL
├── Master (writes)
└── Read Replicas (for message history queries)
```
- Shard by userId or chatId for very large deployments
- Archive old messages (>30 days) to cold storage

### Capacity Planning

**Storage:**
```
Messages: 40K msgs/sec × 86,400 sec/day × 500 bytes = 1.7 TB/day
Inbox: 40K msgs/sec × 10 recipients × 200 bytes = 3.5 GB/sec write
```

**Connection Scaling:**
```
200M concurrent connections
÷ 1M connections per server
= 200 Chat Servers
```

**Network Bandwidth:**
```
40K msgs/sec × 500 bytes = 20 MB/sec inbound
40K msgs/sec × 10 recipients × 500 bytes = 200 MB/sec outbound
```

## 🔒 Security Considerations

### WebSocket Security
- Authenticate via query parameter (production: use token)
- Validate userId matches authenticated user
- Rate limiting per connection (e.g., 10 messages/second)

### Message Security
- Validate sender is participant in chat
- Sanitize message content (XSS prevention)
- Enforce attachment size limits (10MB per file)

### Data Protection
- Encrypt attachments at rest in S3
- Use pre-signed URLs with expiration (1 hour)
- Delete expired messages (30-day TTL)

## 🧪 Testing

### Manual Testing

**1. Connect Two Users**
```bash
# Terminal 1 (User 1)
wscat -c "ws://localhost:8080/ws/chat?userId=user1"

# Terminal 2 (User 2)
wscat -c "ws://localhost:8080/ws/chat?userId=user2"
```

**2. Create Chat (User 1)**
```json
{
  "command": "CREATE_CHAT",
  "payload": {
    "participants": ["user1", "user2"],
    "name": "Test Chat"
  }
}
```

**3. Send Message (User 1)**
```json
{
  "command": "SEND_MESSAGE",
  "payload": {
    "chatId": "chat-abc",
    "message": "Hello User 2!",
    "messageType": "TEXT"
  }
}
```

**4. Verify User 2 Receives**
```
User 2 terminal should show NEW_MESSAGE event
```

**5. Disconnect and Reconnect User 2**
```
Close connection, wait 5 seconds, reconnect
Server automatically syncs undelivered messages from Inbox
```

## 📦 Technology Stack

- **Framework**: Spring Boot 3.2.0
- **Language**: Java 17
- **Database**: H2 (dev) / PostgreSQL (prod)
- **ORM**: Spring Data JPA + Hibernate
- **Cache & Pub/Sub**: Redis 7
- **WebSocket**: Spring WebSocket
- **Build Tool**: Maven

## 🎓 Learning Objectives

This implementation demonstrates:
1. **Real-Time Systems**: WebSocket connection management, bi-directional communication
2. **Message Routing**: Redis Pub/Sub for distributed message delivery
3. **Reliability**: Inbox pattern for guaranteed delivery, ACK protocol
4. **Scalability**: Horizontal scaling, connection density optimization
5. **Data Modeling**: Multi-device support, efficient participant lookups

## 📚 References

- [System Design Interview Guide](https://www.hellointerview.com/learn/system-design/problem-breakdowns/whatsapp)
- [WebSocket Protocol (RFC 6455)](https://datatracker.ietf.org/doc/html/rfc6455)
- [Redis Pub/Sub Documentation](https://redis.io/docs/interact/pubsub/)
- [Spring WebSocket Guide](https://docs.spring.io/spring-framework/reference/web/websocket.html)

## 📝 License

This is a system design educational project. Not for production use.
