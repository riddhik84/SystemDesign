# WhatsApp Platform - Code Walkthrough

This guide walks through the codebase step-by-step.

## Project Structure

```
whatsapp/
├── model/                      # Domain entities
│   ├── Chat.java              # Chat entity (direct/group)
│   ├── ChatParticipant.java   # Bidirectional participant lookup
│   ├── Message.java           # Message content + metadata
│   ├── Inbox.java             # Undelivered message queue
│   ├── Client.java            # Device/connection tracking
│   └── LastSeen.java          # User online status
├── repository/                 # Data access
│   ├── ChatRepository.java
│   ├── MessageRepository.java
│   ├── InboxRepository.java
│   ├── ClientRepository.java
│   └── LastSeenRepository.java
├── service/                    # Business logic
│   ├── WebSocketConnectionService.java  # Connection management
│   ├── ChatService.java                 # Chat CRUD
│   ├── MessageService.java              # Message delivery
│   └── RedisPubSubService.java          # Message routing
├── websocket/                  # WebSocket layer
│   ├── WebSocketMessage.java           # DTOs
│   └── ChatWebSocketHandler.java       # Connection handler
├── controller/                 # REST API
│   ├── ChatController.java
│   └── UserController.java
└── config/                     # Configuration
    ├── WebSocketConfig.java
    ├── RedisConfig.java
    └── SchedulingConfig.java
```

## Architecture Flow

### 1. User Connects via WebSocket

```
Client → ws://server/ws/chat?userId=user123
  ↓
WebSocketConfig.UserHandshakeInterceptor
  - Extracts userId from query parameter
  - Stores in session attributes
  ↓
ChatWebSocketHandler.afterConnectionEstablished()
  ↓
WebSocketConnectionService.registerConnection()
  1. Check max 3 clients per user
  2. Create Client record (online=true)
  3. Store sessionId → clientId mapping
  4. Update LastSeen (online=true)
  5. Subscribe to Redis: user:{userId}
  ↓
MessageService.syncInbox(clientId)
  - Fetch undelivered messages from Inbox
  - Send via WebSocket
```

### 2. Send Message Flow

```
Client sends WebSocket message:
{
  command: "SEND_MESSAGE",
  payload: {chatId, message, messageType}
}
  ↓
ChatWebSocketHandler.handleTextMessage()
  → handleSendMessage()
  ↓
MessageService.sendMessage()
  1. Generate sequence number (per sender)
  2. Save Message to database
  3. Get chat participants
  4. Create Inbox entry for each recipient client
  5. Publish to Redis Pub/Sub
  6. Return messageId to sender
  ↓
RedisPubSubService.publishMessage()
  - For each recipient userId
  - Publish to channel: user:{userId}
  ↓
Redis → Chat Servers subscribed to user channels
  ↓
MessageService.deliverMessageToClient()
  - Look up WebSocketSession
  - Send NEW_MESSAGE via WebSocket
  - Wait for ACK
```

### 3. Receive Message Flow

```
Redis Pub/Sub → Chat Server
  ↓
MessageService.deliverMessageToClient()
  1. Find Client by clientId
  2. Check if online
  3. Get WebSocketSession from connectionService
  4. Build WebSocketMessage (NEW_MESSAGE)
  5. Send via session.sendMessage()
  ↓
Client receives message
  ↓
Client sends ACK:
{
  command: "ACK_MESSAGE",
  payload: {messageId}
}
  ↓
ChatWebSocketHandler.handleAckMessage()
  ↓
MessageService.acknowledgeMessage()
  - Update Inbox: delivered=true
  - Delete from Inbox table
```

## Core Components Deep Dive

### 1. WebSocketConnectionService

**Purpose:** Manage WebSocket connections and user-to-session mapping

**In-Memory State:**
```java
sessionMap: Map<sessionId, WebSocketSession>
sessionToClientMap: Map<sessionId, clientId>
userToSessionMap: Map<userId, sessionId>
```

**Key Methods:**

`registerConnection(userId, session)`
```java
1. Check: countByUserId < 3 (max clients)
2. Create Client entity (online=true)
3. Store mappings in concurrent maps
4. Update LastSeen (online=true)
5. Return clientId
```

`unregisterConnection(sessionId)`
```java
1. Remove from all mappings
2. Mark Client offline in DB
3. Check if any clients still online for user
4. If not, update LastSeen table
```

`getSession(userId)` → WebSocketSession
- Used to send messages to specific user

**Why In-Memory Maps?**
- O(1) lookup for userId → WebSocketSession
- Avoids database query per message
- Lost on server restart (clients reconnect automatically)

### 2. MessageService

**Purpose:** Message storage, delivery, and inbox management

**sendMessage(chatId, senderId, content, type, attachments)**
```java
// 1. Generate sequence number
Long seq = getNextSequenceNumber(senderId);

// 2. Save message to database
Message msg = Message.builder()
    .sequenceNumber(seq)
    .expiresAt(now + 30 days)
    .build();
messageRepository.save(msg);

// 3. Get participants
List<String> participants = 
    participantRepository.findActiveUserIdsByChatId(chatId);

// 4. Create Inbox entries (per client, not per user!)
for (String userId : participants) {
    if (!userId.equals(senderId)) {
        List<Client> clients = clientRepository.findByUserId(userId);
        for (Client client : clients) {
            Inbox inbox = Inbox.builder()
                .clientId(client.getId())
                .messageId(msg.getId())
                .delivered(false)
                .expiresAt(now + 30 days)
                .build();
            inboxRepository.save(inbox);
        }
    }
}

// 5. Publish to Redis (best-effort)
redisPubSubService.publishMessage(msg, participants);

return msg.getId();
```

**Why Inbox per Client?**
```
User has 3 devices:
  - Phone (client1)
  - Tablet (client2)
  - Desktop (client3)

Message arrives:
  - Inbox entry for client1 (undelivered)
  - Inbox entry for client2 (undelivered)
  - Inbox entry for client3 (undelivered)

Phone ACKs:
  - Delete inbox entry for client1

Tablet still has undelivered entry
Desktop offline → syncs on reconnect
```

**syncInbox(clientId)**
```java
// 1. Fetch undelivered messages
List<Inbox> undelivered = inboxRepository
    .findByClientIdAndDeliveredFalseOrderByTimestamp(clientId);

// 2. Load full message for each
for (Inbox entry : undelivered) {
    Message msg = messageRepository.findById(entry.getMessageId());
    deliverMessageToClient(clientId, msg);
}
```

**acknowledgeMessage(clientId, messageId)**
```java
inboxRepository.markAsDelivered(clientId, messageId);
// Later: scheduled cleanup job deletes delivered messages
```

### 3. RedisPubSubService

**Purpose:** Route messages between Chat Servers

**Channel Strategy:**
```
Small groups (<25 participants):
  Per-user channels: user:123, user:456, user:789
  Each Chat Server subscribes to channels for its connected users

Large groups (>25 participants):
  Per-chat channel: chat:group-456
  All Chat Servers with participants subscribe
```

**publishMessage(message, recipientUserIds)**
```java
// Build JSON payload
Map<String, Object> payload = Map.of(
    "messageId", message.getId(),
    "chatId", message.getChatId(),
    "senderId", message.getSenderId(),
    "content", message.getContent(),
    "sequenceNumber", message.getSequenceNumber()
);

String json = objectMapper.writeValueAsString(payload);

// Publish to each recipient's channel
for (String userId : recipientUserIds) {
    String channel = "user:" + userId;
    redisTemplate.convertAndSend(channel, json);
}
```

**Why Redis Pub/Sub?**
- Lightweight message distribution
- Decouples Chat Servers
- Scales horizontally (Redis Cluster)
- "At most once" delivery acceptable (Inbox provides durability)

**Alternative Considered:**
- Direct server-to-server: Requires service discovery, complex routing
- Database queue: High read load, polling latency

### 4. ChatWebSocketHandler

**Purpose:** Handle WebSocket lifecycle and commands

**Connection Lifecycle:**
```
afterConnectionEstablished()
  → registerConnection()
  → syncInbox()

handleTextMessage()
  → Parse command
  → Route to handler

afterConnectionClosed()
  → unregisterConnection()
  → Update LastSeen
```

**Command Routing:**
```java
switch (command) {
    case CREATE_CHAT:
        chatService.createChat(...);
        break;
    case SEND_MESSAGE:
        messageService.sendMessage(...);
        break;
    case ACK_MESSAGE:
        messageService.acknowledgeMessage(...);
        break;
    case HEARTBEAT:
        updateHeartbeat();
        sendHeartbeatResponse();
        break;
}
```

**Heartbeat Protocol:**
```
Client → HEARTBEAT {sequenceNumber: 123} (every 30 sec)
Server → HEARTBEAT_RESPONSE {sequenceNumber: 123}

Purpose:
1. Detect disconnections
2. Piggyback sequence numbers for gap detection
```

## Data Flow Examples

### Example 1: Two Users Chatting

```
User A (online) → User B (online)

1. User A sends message via WebSocket:
   {command: "SEND_MESSAGE", chatId: "chat1", message: "Hi"}

2. ChatWebSocketHandler → MessageService.sendMessage()
   - Save Message (id=msg1, seq=1)
   - Create Inbox entries:
     * client_B1 → msg1 (delivered=false)
     * client_B2 → msg1 (delivered=false)  [B has 2 devices]
   - Publish to Redis: user:userB

3. Redis → Chat Server hosting User B
   - MessageService receives Redis message
   - Looks up User B's sessions
   - Sends to both devices via WebSocket

4. User B's devices receive message
   - Device 1 sends ACK → inbox for client_B1 deleted
   - Device 2 sends ACK → inbox for client_B2 deleted
```

### Example 2: Offline Delivery

```
User A (online) → User C (offline)

1. User A sends message:
   MessageService.sendMessage()
   - Save Message (id=msg2)
   - Create Inbox: client_C1 → msg2 (delivered=false)
   - Publish to Redis: user:userC (no subscribers)

2. User C reconnects:
   afterConnectionEstablished()
   → syncInbox(client_C1)
   → Finds msg2 in Inbox
   → Sends via WebSocket

3. User C's device ACKs:
   → Delete from Inbox
```

### Example 3: Group Chat (5 participants)

```
User A sends message to group (users: A, B, C, D, E)

1. MessageService.sendMessage()
   - Save Message (chatId=group1, id=msg3)
   - Get participants: [B, C, D, E] (exclude sender)
   - Create Inbox entries for each recipient's clients:
     * B has 2 devices → 2 inbox entries
     * C has 1 device → 1 inbox entry
     * D has 3 devices → 3 inbox entries
     * E has 1 device → 1 inbox entry
     Total: 7 inbox entries

2. Publish to Redis:
   - user:B → Chat Server 1
   - user:C → Chat Server 2
   - user:D → Chat Server 1
   - user:E → Chat Server 3

3. Each Chat Server delivers to connected clients
4. Clients ACK → Delete inbox entries
```

## Configuration

### WebSocketConfig

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .addInterceptors(new UserHandshakeInterceptor())
                .setAllowedOrigins("*");
    }
}
```

**UserHandshakeInterceptor:**
- Extracts userId from query param
- Stores in WebSocketSession attributes
- Production: validate JWT token instead

### RedisConfig

```java
@Bean
public RedisMessageListenerContainer container() {
    RedisMessageListenerContainer container = 
        new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    return container;
}
```

Enables pub/sub subscriptions

### SchedulingConfig

```java
@Scheduled(cron = "0 0 2 * * ?")  // 2 AM daily
public void cleanupExpiredData() {
    messageService.cleanupExpiredMessages();
}
```

Deletes messages and inbox entries with `expiresAt < now()`

## Scaling Considerations

### Connection Density

```java
// Production tuning
ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
executor.setCorePoolSize(500);
executor.setMaxPoolSize(2000);
executor.setQueueCapacity(10000);

// Supports ~1M concurrent WebSocket connections per server
```

### Database Sharding

```java
// Shard by userId for horizontal scaling
int shardId = userId.hashCode() % NUM_SHARDS;
DataSource dataSource = dataSources.get(shardId);

// OR shard by chatId for group chats
int shardId = chatId.hashCode() % NUM_SHARDS;
```

### Redis Cluster

```java
// Production: Redis Cluster for pub/sub
@Bean
public RedisConnectionFactory redisConnectionFactory() {
    RedisClusterConfiguration config = 
        new RedisClusterConfiguration(clusterNodes);
    return new LettuceConnectionFactory(config);
}

// Channels partitioned across cluster nodes
```

## Common Questions

### Q: How do you prevent duplicate messages?

**A:** Idempotency via messageId
```java
// Client tracks received messageIds
Set<String> receivedIds = new HashSet<>();

onMessage(message) {
    if (receivedIds.contains(message.messageId)) {
        return;  // Duplicate, ignore
    }
    receivedIds.add(message.messageId);
    displayMessage(message);
}
```

### Q: How do you handle reconnections?

**A:** Automatic reconnect + inbox sync
```javascript
ws.onclose = () => {
    setTimeout(() => {
        ws = new WebSocket(url);
        // Server auto-syncs inbox on connect
    }, 1000);
};
```

### Q: How do you scale Redis Pub/Sub?

**A:** Redis Cluster with channel partitioning
```
user:1 - user:100M → Shard 1
user:100M - user:200M → Shard 2
...

Chat Servers subscribe to multiple shards
```

### Q: What happens if Inbox table grows too large?

**A:** Scheduled cleanup + partitioning
```java
// Delete expired entries
DELETE FROM inbox WHERE expiresAt < NOW();

// Partition by date
inbox_2024_01
inbox_2024_02
...
```

## Running the Code

```bash
# Start Redis
docker run -d -p 6379:6379 redis:alpine

# Run app
mvn spring-boot:run

# Connect via WebSocket
wscat -c "ws://localhost:8080/ws/chat?userId=user1"

# Send message
{"command":"SEND_MESSAGE","payload":{"chatId":"chat1","message":"Hello","messageType":"TEXT"}}
```

## Key Takeaways

1. **In-Memory Mappings**: userId → WebSocketSession for O(1) delivery
2. **Inbox Pattern**: Guaranteed delivery despite failures
3. **Per-Client Inbox**: Enables multi-device sync
4. **Redis Pub/Sub**: Decouples Chat Servers
5. **Sequence Numbers**: Gap detection for reliability
6. **Heartbeat Protocol**: Dual-purpose liveness + sequence sync

