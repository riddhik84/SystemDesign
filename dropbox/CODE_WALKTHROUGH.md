# Dropbox System Design — Code Walkthrough

This guide explains the codebase architecture, traces every major flow through the code, and explains the design decisions embedded in the implementation.

---

## Suggested Reading Order

For the fastest comprehension, read files in this order:

1. `model/FileStatus.java` — understand the state machine first
2. `model/FileMetadata.java` + `model/FileChunk.java` — core data shapes
3. `model/SharedFile.java` + `model/SharedFileId.java` — sharing model
4. `model/` DTOs — the API contract (InitiateUploadRequest → Response, CompleteUploadRequest)
5. `model/FileChangeEvent.java` — the sync event shape
6. `config/AppProperties.java` — what's configurable
7. `storage/S3StorageService.java` — the S3 integration boundary
8. `service/FileUploadService.java` — the upload orchestrator (most complex service)
9. `service/FileDownloadService.java` — download + access check
10. `service/FileSharingService.java` — share/unshare/hasAccess
11. `service/FileSyncService.java` — change queries + Redis publish
12. `controller/FileController.java` — the HTTP API surface
13. `controller/SyncWebSocketHandler.java` — WebSocket + Redis subscription
14. `config/` — Spring wiring (S3Config, RedisConfig, WebSocketConfig)
15. `exception/GlobalExceptionHandler.java` — error handling
16. Tests — one test file per service + controller

---

## Package Map

```
com.systemdesign.dropbox/
│
├── DropboxApplication.java          Entry point + @EnableConfigurationProperties
│
├── model/                           Pure data — no Spring dependencies
│   ├── FileStatus.java              Enum: PENDING | UPLOADING | READY | DELETED
│   ├── FileMetadata.java            JPA entity: file_metadata table
│   ├── FileChunk.java               JPA entity: file_chunks table
│   ├── SharedFile.java              JPA entity: shared_files table
│   ├── SharedFileId.java            Serializable composite PK helper
│   ├── InitiateUploadRequest.java   DTO: POST /files/initiate body
│   ├── PresignedChunkUrl.java       DTO: one entry in initiate response
│   ├── InitiateUploadResponse.java  DTO: POST /files/initiate response
│   ├── ChunkEtag.java               DTO: one entry in complete request
│   ├── CompleteUploadRequest.java   DTO: POST /files/{id}/complete body
│   ├── ShareRequest.java            DTO: POST /files/{id}/share body
│   └── FileChangeEvent.java         DTO: sync event (WebSocket + polling)
│
├── repository/                      Spring Data JPA interfaces
│   ├── FileMetadataRepository.java
│   ├── FileChunkRepository.java
│   └── SharedFileRepository.java
│
├── storage/                         External system boundary
│   └── S3StorageService.java        All S3 SDK operations
│
├── service/                         Business logic
│   ├── FileUploadService.java       Orchestrates initiate + complete
│   ├── FileDownloadService.java     Access check + presigned URL generation
│   ├── FileSharingService.java      Share/unshare + hasAccess
│   └── FileSyncService.java         getChanges() + publishChange() to Redis
│
├── controller/                      HTTP + WebSocket entry points
│   ├── FileController.java          6 REST endpoints
│   └── SyncWebSocketHandler.java    WebSocket connection lifecycle
│
├── config/                          Spring configuration beans
│   ├── AppProperties.java           @ConfigurationProperties: app.*
│   ├── S3Config.java                AmazonS3 bean (LocalStack-aware)
│   ├── RedisConfig.java             StringRedisTemplate + listener container
│   └── WebSocketConfig.java         Registers /ws/sync endpoint
│
└── exception/
    ├── FileNotFoundException.java   → 404
    ├── AccessDeniedException.java   → 403
    ├── UploadIncompleteException.java → 409
    └── GlobalExceptionHandler.java  RFC 7807 ProblemDetail responses
```

---

## Data Model Deep Dive

### FileMetadata — The Central Entity

```java
@Entity @Table(name = "file_metadata")
public class FileMetadata {
    String fileId;       // UUID — stable business key, exposed in API
    String ownerId;      // Who uploaded it
    FileStatus status;   // UPLOADING → READY (the gate for all operations)
    String s3Key;        // "files/{ownerId}/{fileId}" — the S3 object key
    String s3UploadId;   // Non-null only while UPLOADING; cleared on /complete
    Integer totalChunks; // How many FileChunk rows to expect
    LocalDateTime updatedAt; // Indexed; sync queries use "updatedAt > since"
}
```

**The `s3UploadId` lifecycle is critical:** it's created at `/initiate` (S3 returns it), stored here, used for presigned URL generation, passed to `completeMultipartUpload`, then set to `null`. If `s3UploadId` is non-null, the multipart upload is still in flight on S3.

### FileChunk — Part Tracking

```java
@Entity @Table(name = "file_chunks")
public class FileChunk {
    String fileId;      // Not a JPA FK — intentional for insert performance
    Integer chunkNumber; // 1-based (matches S3 part numbering)
    String etag;        // Null until client provides it in /complete
    boolean uploaded;   // false → true when /complete runs
}
```

Why not a JPA `@ManyToOne` FK? At 5,800 uploads/sec × 7 chunks avg = 40k inserts/sec. JPA FK cascade tracking adds overhead. The `UNIQUE(file_id, chunk_number)` constraint provides the integrity guarantee without the ORM overhead.

### SharedFile — The Access-Control Table

```java
@Entity @IdClass(SharedFileId.class)
public class SharedFile {
    @Id String fileId;             // Composite PK
    @Id String sharedWithUserId;   // Composite PK
    String permission;             // "READ" or "WRITE"
}
```

The composite PK `(fileId, sharedWithUserId)` enforces uniqueness at the DB level. A file can only be shared once with a given user; re-sharing replaces the existing record (delete + insert in `shareFile()`).

### State Machine Diagram

```
[new request]
     │
     ▼
  PENDING
     │ (initiateMultipartUpload succeeds)
     ▼
 UPLOADING ──────────────────────────────► DELETED
     │ (all chunks uploaded, /complete called)  (abort)
     ▼
   READY ──────────────────────────────── DELETED
     │ (download allowed, sharing allowed)      (owner deletes)
```

**READY is the only state from which downloads are allowed.** `FileDownloadService` checks `status != READY` and throws `UploadIncompleteException` (409) otherwise.

---

## Upload Flow — Step by Step Through Code

### Phase 1: POST /files/initiate

**1. Request arrives at `FileController.initiateUpload()`**
```java
@PostMapping("/initiate")
public ResponseEntity<InitiateUploadResponse> initiateUpload(
        @RequestHeader("X-User-Id") String userId,
        @Valid @RequestBody InitiateUploadRequest request) {
    return ResponseEntity.status(201).body(
            fileUploadService.initiateUpload(userId, request));
}
```
`@Valid` triggers Bean Validation on `InitiateUploadRequest` — blank fileName or zero bytes returns 400.

**2. `FileUploadService.initiateUpload()` runs:**

```java
// Step 1: Calculate chunk count
int totalChunks = (int) Math.ceil(fileSizeBytes / CHUNK_SIZE_BYTES);
totalChunks = Math.max(totalChunks, 1); // edge case: tiny file

// Step 2: Generate fileId and s3Key
String fileId = UUID.randomUUID().toString();
String s3Key = "files/" + ownerId + "/" + fileId;

// Step 3: Initiate S3 multipart upload — reserves the slot
String uploadId = s3StorageService.initiateMultipartUpload(s3Key);
// → calls: AmazonS3.initiateMultipartUpload(new InitiateMultipartUploadRequest(bucket, s3Key))
// → returns: result.getUploadId() (e.g. "VXBsb2FkIElEIGZvciA...")

// Step 4: Save FileMetadata in UPLOADING state
FileMetadata metadata = FileMetadata.builder()
        .fileId(fileId).ownerId(ownerId).status(UPLOADING)
        .s3Key(s3Key).s3UploadId(uploadId).totalChunks(totalChunks)...
        .build();
fileMetadataRepository.save(metadata);

// Step 5: For each chunk, save FileChunk row + generate presigned URL
for (int partNumber = 1; partNumber <= totalChunks; partNumber++) {
    fileChunkRepository.save(FileChunk.builder()
            .fileId(fileId).chunkNumber(partNumber)
            .chunkSizeBytes(calculateChunkSize(...))
            .uploaded(false).build());

    String url = s3StorageService.generatePresignedUploadUrl(
            s3Key, uploadId, partNumber, Duration.ofMinutes(5));
    // → calls: new GeneratePresignedUrlRequest(bucket, s3Key, PUT)
    //           .addRequestParameter("uploadId", uploadId)
    //           .addRequestParameter("partNumber", String.valueOf(partNumber))
    // → returns: s3Client.generatePresignedUrl(request).toString()
    
    chunkUrls.add(PresignedChunkUrl.builder()
            .chunkNumber(partNumber).presignedUrl(url).build());
}
```

**The client now has:** `fileId`, and N presigned PUT URLs, one per chunk.

**The client does (outside our service):**
- Split the file into N chunks of 8 MB each.
- PUT each chunk to its presigned URL directly to S3.
- S3 returns an `ETag` header per PUT. The client collects these.

---

### Phase 2: POST /files/{fileId}/complete

**1. `FileController.completeUpload()` receives the ETags.**

**2. `FileUploadService.completeUpload()` runs:**

```java
// Step 1: Load and validate ownership
FileMetadata metadata = fileMetadataRepository.findByFileId(fileId)
        .orElseThrow(() -> new FileNotFoundException(fileId));
if (!metadata.getOwnerId().equals(ownerId))
    throw new AccessDeniedException(ownerId, fileId);

// Step 2: Validate chunk count matches
if (req.getChunkEtags().size() != metadata.getTotalChunks())
    throw new IllegalArgumentException("Expected N chunk etags, got M");

// Step 3: Update each FileChunk with its ETag
Map<Integer, String> etagMap = req.getChunkEtags().stream()
        .collect(toMap(ChunkEtag::getChunkNumber, ChunkEtag::getEtag));
for (FileChunk chunk : fileChunkRepository.findByFileIdOrderByChunkNumber(fileId)) {
    chunk.setEtag(etagMap.get(chunk.getChunkNumber()));
    chunk.setUploaded(true);
    fileChunkRepository.save(chunk);
}

// Step 4: Sort PartETags by number (S3 REQUIRES sorted order)
List<PartETag> partETags = req.getChunkEtags().stream()
        .sorted(Comparator.comparingInt(ChunkEtag::getChunkNumber))
        .map(ce -> new PartETag(ce.getChunkNumber(), ce.getEtag()))
        .collect(toList());

// Step 5: Tell S3 to assemble the parts
s3StorageService.completeMultipartUpload(s3Key, uploadId, partETags);
// → AmazonS3.completeMultipartUpload(
//     new CompleteMultipartUploadRequest(bucket, s3Key, uploadId, partETags))
// → S3 assembles parts atomically — object becomes accessible

// Step 6: Mark READY, clear uploadId
metadata.setStatus(READY);
metadata.setS3UploadId(null);
fileMetadataRepository.save(metadata);

// Step 7: Notify connected devices
fileSyncService.publishChange(ownerId, FileChangeEvent{CREATED, READY, ...});
```

**Key invariant:** `completeMultipartUpload` is transactional. The `FileMetadata.status` flips to `READY` only after S3 confirms the assembly. If S3 throws, the exception propagates and status stays `UPLOADING`.

---

## Download Flow — Step by Step

**1. `FileController.getPresignedDownloadUrl()` receives GET /files/{id}/presigned-url.**

**2. `FileDownloadService.getDownloadUrl(requesterId, fileId)` runs:**

```java
// Load metadata
FileMetadata metadata = fileMetadataRepository.findByFileId(fileId)
        .orElseThrow(() -> new FileNotFoundException(fileId));

// Guard: deleted files look like not-found (don't reveal tombstones)
if (metadata.getStatus() == DELETED)
    throw new FileNotFoundException(fileId);

// Guard: incomplete upload
if (metadata.getStatus() != READY)
    throw new UploadIncompleteException(fileId);

// Access check: owner OR has shared record
if (!fileSharingService.hasAccess(requesterId, fileId, metadata.getOwnerId()))
    throw new AccessDeniedException(requesterId, fileId);

// Generate time-limited URL
String url = s3StorageService.generatePresignedDownloadUrl(
        metadata.getS3Key(), Duration.ofMinutes(5));
// → new GeneratePresignedUrlRequest(bucket, s3Key, GET).withExpiration(now + 5min)
// → s3Client.generatePresignedUrl(request).toString()
```

**3. `FileSharingService.hasAccess()` check:**
```java
public boolean hasAccess(String userId, String fileId, String ownerId) {
    if (userId.equals(ownerId)) return true;   // Owner always has access
    return sharedFileRepository.existsByFileIdAndSharedWithUserId(fileId, userId);
    // → SELECT EXISTS(SELECT 1 FROM shared_files WHERE file_id=? AND shared_with_user_id=?)
}
```

**4. Client receives the URL and downloads directly from S3/CDN.** The app server serves only the URL (a short string), not the bytes.

---

## Sync Flow — WebSocket + Polling

### WebSocket Connection Lifecycle

**`SyncWebSocketHandler.afterConnectionEstablished()`:**
```java
// 1. Extract userId from query string: ws://host/ws/sync?userId=alice
String userId = extractUserId(session);   // parses URI query params

// 2. Register session in memory map
sessionMap.put(userId, session);   // ConcurrentHashMap

// 3. Create Redis subscriber for this user's channel
MessageListener listener = (message, pattern) -> forwardToSession(userId, message);
listenerMap.put(userId, listener);

// 4. Register subscriber on Redis container
redisListenerContainer.addMessageListener(
        listener, new ChannelTopic("file-changes:alice"));
// Redis will now call listener.onMessage() for every publish to that channel
```

**On Redis message (publish from `FileSyncService.publishChange()`):**
```java
private void forwardToSession(String userId, Message message) {
    WebSocketSession session = sessionMap.get(userId);
    if (session == null || !session.isOpen()) return;   // client disconnected
    String payload = new String(message.getBody());     // JSON of FileChangeEvent
    session.sendMessage(new TextMessage(payload));       // push to WebSocket
}
```

**`afterConnectionClosed()` cleanup:**
```java
sessionMap.remove(userId);
redisListenerContainer.removeMessageListener(listener, channelTopic);
// Deregistering prevents ghost listeners from leaking memory after disconnect
```

### Redis Publish (from FileSyncService)

```java
public void publishChange(String userId, FileChangeEvent event) {
    String channel = "file-changes:" + userId;
    String json = objectMapper.writeValueAsString(event);
    stringRedisTemplate.convertAndSend(channel, json);
    // Redis PUBLISH file-changes:alice "{fileId:..., eventType:CREATED, ...}"
}
```

The Redis `PUBLISH` command fan-out reaches every app server instance subscribed to that channel via the `RedisMessageListenerContainer`. This is what makes the system horizontally scalable — any app server can handle uploads for any user, and all WebSocket servers for that user receive the event.

### Polling Fallback (GET /files/changes?since=T)

```java
// FileSyncService.getChanges("alice", Instant.parse("2024-06-01T00:00:00Z"))

// 1. Query owned files updated after T
List<FileMetadata> owned = fileMetadataRepository
        .findByOwnerIdAndStatusAndUpdatedAtAfter("alice", READY, sinceLocal);

// 2. Query shared file IDs
List<String> sharedIds = sharedFileRepository.findBySharedWithUserId("alice")
        .stream().map(SharedFile::getFileId).toList();

// 3. Query metadata for shared files updated after T
List<FileMetadata> shared = fileMetadataRepository
        .findByFileIdInAndStatusAndUpdatedAtAfter(sharedIds, READY, sinceLocal);

// 4. Build events from union
// Both sets get eventType = "UPDATED" (it's a catch-all for polling)
```

**Why two queries instead of a JOIN?** Avoids a cross-table join that would grow expensive at scale. The shared fileId list is typically small (hundreds of files). The two-query pattern is easier to cache individually (e.g., cache `sharedIds` for a TTL).

---

## Error Handling

### Exception Hierarchy

```
RuntimeException
├── FileNotFoundException      → 404  (fileId missing or DELETED)
├── AccessDeniedException      → 403  (user has no access)
└── UploadIncompleteException  → 409  (file not yet READY)
```

### `GlobalExceptionHandler` — RFC 7807 ProblemDetail

All exceptions are mapped to Spring 6's `ProblemDetail` response format:
```json
{
  "type": "urn:dropbox:error:file-not-found",
  "title": "Not Found",
  "status": 404,
  "detail": "File not found: file-abc123"
}
```

Validation errors include a `fieldErrors` map:
```json
{
  "type": "urn:dropbox:error:validation",
  "status": 400,
  "detail": "Request validation failed",
  "fieldErrors": { "fileName": "fileName is required" }
}
```

**Why ProblemDetail?** It's the RFC 7807 standard, supported natively in Spring 6, and OpenAPI/springdoc can describe error shapes automatically. No custom error envelope needed.

### The Redis Resilience Pattern

In `FileSyncService.publishChange()`:
```java
try {
    stringRedisTemplate.convertAndSend(channel, json);
} catch (Exception e) {
    log.error("Failed to publish to Redis channel {}", channel, e);
    // Intentionally swallowed — Redis failure is non-fatal
}
```

**Why swallow the exception?** The upload itself succeeded (PostgreSQL committed). Failing the HTTP response because Redis is down would cause the client to retry the entire upload, duplicating data. Instead: the real-time push fails silently, clients fall back to polling, and the data is eventually consistent. This is the correct AP trade-off.

---

## S3 Integration Explained

### `S3Config.java` — LocalStack Support

```java
if (StringUtils.hasText(endpointOverride)) {
    // LocalStack / custom S3-compatible endpoint
    return AmazonS3ClientBuilder.standard()
            .withEndpointConfiguration(
                    new EndpointConfiguration(endpointOverride, region))
            .withCredentials(new AWSStaticCredentialsProvider(
                    new BasicAWSCredentials("test", "test")))
            .withPathStyleAccessEnabled(true)   // LocalStack needs path-style
            .build();
}
// Production: use DefaultAWSCredentialsProviderChain (env vars / instance profile)
return AmazonS3ClientBuilder.standard()
        .withRegion(region)
        .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
        .build();
```

Set `S3_ENDPOINT=http://localhost:4566` to run against LocalStack without real AWS credentials. The `AmazonS3` interface is the same — no code changes in `S3StorageService`.

### Presigned URL Generation (SDK Detail)

```java
// For UPLOAD (part of multipart):
GeneratePresignedUrlRequest request =
        new GeneratePresignedUrlRequest(bucket, s3Key, HttpMethod.PUT)
                .withExpiration(new Date(now + expiry.toMillis()));
request.addRequestParameter("uploadId", uploadId);
request.addRequestParameter("partNumber", String.valueOf(partNumber));

// For DOWNLOAD:
GeneratePresignedUrlRequest request =
        new GeneratePresignedUrlRequest(bucket, s3Key, HttpMethod.GET)
                .withExpiration(new Date(now + expiry.toMillis()));
```

The `uploadId` and `partNumber` parameters embedded in the presigned upload URL tell S3 which multipart upload and which part this PUT belongs to. Without these, the PUT would create a new object instead of uploading a part.

---

## Test Structure

### Unit Test Philosophy
All tests use `@ExtendWith(MockitoExtension.class)` — no Spring context, no real dependencies. This keeps tests fast (< 50ms each) and focused on business logic.

| Test Class | What it tests |
|---|---|
| `FileUploadServiceTest` | Chunk count math, s3Key format, status progression, PartETag ordering |
| `FileDownloadServiceTest` | Access checks (owner/shared/denied), status guards (UPLOADING/DELETED) |
| `FileSharingServiceTest` | Owner-only sharing, composite PK upsert, hasAccess logic |
| `FileSyncServiceTest` | Change queries, Redis publish channel naming, failure resilience |
| `FileControllerTest` | HTTP status codes, request validation, exception-to-status mapping |

### Controller Test Approach
`FileControllerTest` uses `MockMvcBuilders.standaloneSetup()` — no `@SpringBootTest`, no `@WebMvcTest`. This provides full MockMvc request/response testing with manually wired mocks:
```java
mockMvc = MockMvcBuilders
        .standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(converter)   // custom ObjectMapper with JavaTimeModule
        .build();
```
The custom `ObjectMapper` with `JavaTimeModule` is critical — without it, `Instant` serialization fails in tests.

### Key Test Invariants Verified

1. **Chunk count calculation** — `20MB / 8MB = 3 chunks` (`FileUploadServiceTest.initiateUpload_largeFile_multipleChunks`)
2. **PartETag sort order** — ETags provided out of order must be sorted before S3 call (`FileUploadServiceTest.completeUpload_partETagsSortedBeforeS3Call`)
3. **Redis failure non-propagation** — `FileSyncServiceTest.publishChange_redisFailure_doesNotThrow`
4. **DELETED file returns 404 not 403** — Don't reveal tombstones (`FileDownloadServiceTest.getDownloadUrl_deleted_throwsNotFound`)
5. **Owner always has access** — No SharedFile needed (`FileSharingServiceTest.hasAccess_owner_returnsTrue`)

---

## Dependency Flow Diagram

```
FileController
    ├── FileUploadService
    │       ├── FileMetadataRepository  (JPA)
    │       ├── FileChunkRepository     (JPA)
    │       ├── S3StorageService        (AWS SDK)
    │       └── FileSyncService ──────────────────────────┐
    │                                                     │
    ├── FileDownloadService                               │
    │       ├── FileMetadataRepository  (JPA)             │
    │       ├── FileSharingService ──────────────────────┐│
    │       └── S3StorageService        (AWS SDK)        ││
    │                                                    ││
    ├── FileSharingService                               ││
    │       ├── FileMetadataRepository  (JPA)            ││
    │       ├── SharedFileRepository    (JPA)            ││
    │       └── FileSyncService ◄───────────────────────┘│
    │                                                     │
    └── FileSyncService ◄─────────────────────────────────┘
            ├── FileMetadataRepository  (JPA)
            ├── SharedFileRepository    (JPA)
            └── StringRedisTemplate     (Redis)

SyncWebSocketHandler (WebSocket)
    └── RedisMessageListenerContainer   (Redis)
```

**Circular dependency:** `FileUploadService` → `FileSyncService` and `FileSharingService` → `FileSyncService`. Spring resolves this cleanly because these are separate service beans with no constructor circularity.

---

## Key Invariants

These are the assertions that must always hold in a correct running system:

1. **FileMetadata with status=UPLOADING always has s3UploadId != null.**
   If `s3UploadId` is null but status is UPLOADING, the upload cannot be completed — orphaned multipart upload on S3.

2. **FileMetadata with status=READY always has s3UploadId = null.**
   After `completeMultipartUpload`, the upload ID is no longer valid. Storing it would mislead retry logic.

3. **count(file_chunks WHERE file_id=X AND uploaded=true) == totalChunks** when status=READY.
   Checked implicitly by the `chunkEtags.size() != totalChunks` guard in `completeUpload`.

4. **No download is served for a non-READY file.**
   `FileDownloadService` enforces `status == READY` before generating any URL. UPLOADING → 409. DELETED → 404.

5. **Only the file owner can share or unshare.**
   `FileSharingService.shareFile()` and `unshareFile()` verify `metadata.getOwnerId().equals(ownerId)`.

6. **PartETags passed to S3.completeMultipartUpload are sorted by partNumber.**
   S3 assembles parts in the order listed in the CompleteMultipartUpload request. Out-of-order ETags would produce a corrupted file.

7. **Redis publish failure never fails the primary write.**
   The `try/catch` in `FileSyncService.publishChange()` swallows Redis exceptions. The PostgreSQL commit has already succeeded by the time `publishChange` is called.
