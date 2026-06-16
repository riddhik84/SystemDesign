# Dropbox-Like File Storage and Sync — System Design

---

## 1. Problem Statement & Requirements

### Functional Requirements
1. **Upload files** up to 50 GB — chunked multipart upload via presigned URLs
2. **Download files** — low-latency delivery via CDN-signed URLs
3. **Share files** with other users (read or write access)
4. **Sync files across devices** — real-time push via WebSocket, polling fallback for reconnects

### Non-Functional Requirements
| Requirement | Target |
|---|---|
| Availability | 99.99% (HA preferred over strong consistency) |
| Max file size | 50 GB |
| Download latency | < 100 ms for cached CDN hits |
| Upload latency | Near-zero server load (direct-to-S3) |
| Consistency model | Eventual — devices catch up within seconds |
| Durability | 11 nines (S3 Standard) |
| Security | Signed URLs with short expiry (5 min default) |

### Out of Scope
- File versioning / conflict merge (last-write-wins)
- Full-text indexing of file contents
- Billing / quota enforcement
- Mobile offline editing

---

## 2. Capacity Estimation

### Storage

| Assumption | Value |
|---|---|
| Total registered users | 500 million |
| DAU | 100 million |
| Files per user (avg) | 2,000 |
| Average file size | 10 MB |
| Total files | 1 trillion |
| Total storage | **10 PB** |
| Annual growth | ~20% → 12 PB next year |

### Throughput

**Uploads:**
- DAU: 100M users
- 5 new files/user/day × 100M users = 500M uploads/day
- 500M / 86,400 s ≈ **5,800 uploads/sec (peak ~15k/sec)**
- At 10 MB avg: 5,800 × 10 MB = **58 GB/sec upload throughput** — bypassed by direct-to-S3

**Downloads:**
- ~10 downloads/user/day → ~11,600 download URL requests/sec
- Actual bytes served by CDN — not the application server

**Sync events (WebSocket publishes):**
- ~1 change event per upload → 5,800 Redis publishes/sec
- Each event fans out to at most ~5 devices per user → 29k WebSocket pushes/sec

**Database:**
- FileMetadata writes: 5,800/sec
- FileChunk writes: 5,800 × 7 chunks avg = ~40k/sec (batched)
- Presigned URL reads (download): 11,600/sec

### Storage Breakdown
```
S3 objects:       10 PB
PostgreSQL rows:  ~1 TB (metadata only — text fields, no binary)
Redis:            ~1 GB working set (active WebSocket sessions × small payloads)
```

---

## 3. Core Entities & Data Model

### `file_metadata` (PostgreSQL)
| Column | Type | Description |
|---|---|---|
| id | BIGSERIAL PK | Internal row ID |
| file_id | VARCHAR(36) UNIQUE | Business key (UUID) |
| file_name | VARCHAR(500) | Original filename |
| file_size_bytes | BIGINT | Total file size |
| mime_type | VARCHAR(200) | MIME type |
| owner_id | VARCHAR(200) | Uploader's userId |
| status | VARCHAR(20) | PENDING → UPLOADING → READY → DELETED |
| s3_key | VARCHAR(500) | S3 object key: `files/{ownerId}/{fileId}` |
| s3_upload_id | VARCHAR(200) | S3 multipart upload ID (null after completion) |
| total_chunks | INTEGER | Number of parts |
| checksum | VARCHAR(64) | SHA-256 of assembled file |
| compressed | BOOLEAN | Whether client pre-compressed |
| created_at | TIMESTAMP | Creation time |
| updated_at | TIMESTAMP | Last modification time (indexed for sync) |

**Key indexes:**
- `idx_file_metadata_owner_id` — list files by owner
- `idx_file_metadata_updated_at DESC` — sync polling: "files updated since T"
- `idx_file_metadata_owner_status` — composite for filtered owner queries

### `file_chunks` (PostgreSQL)
| Column | Type | Description |
|---|---|---|
| id | BIGSERIAL PK | |
| file_id | VARCHAR(36) | References file_metadata.file_id |
| chunk_number | INTEGER | 1-based part number (S3 convention) |
| etag | VARCHAR(200) | S3 ETag returned by PUT response |
| chunk_size_bytes | BIGINT | Bytes in this chunk |
| checksum | VARCHAR(64) | SHA-256 of this chunk (for dedup) |
| uploaded | BOOLEAN | false until client confirms |

**UNIQUE(file_id, chunk_number)** — prevents duplicate chunk rows.

### `shared_files` (PostgreSQL)
| Column | Type | Description |
|---|---|---|
| file_id | VARCHAR(36) PK | |
| shared_with_user_id | VARCHAR(200) PK | Composite PK |
| shared_by_user_id | VARCHAR(200) | Owner who granted access |
| permission | VARCHAR(10) | "READ" or "WRITE" |
| shared_at | TIMESTAMP | Grant time |

### Entity Relationships
```
file_metadata (1) ────< file_chunks (many)
file_metadata (1) ────< shared_files (many)
```

---

## 4. API Design

All endpoints accept and return JSON. Authentication is via `X-User-Id` header (simplified; production uses JWT).

### POST /files/initiate
**Purpose:** Start a chunked upload. Backend initiates S3 multipart upload, returns presigned PUT URLs.

**Request:**
```json
{
  "fileName": "video.mp4",
  "fileSizeBytes": 52428800,
  "mimeType": "video/mp4",
  "checksum": "sha256-abc123",
  "compressed": false
}
```

**Response 201:**
```json
{
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "totalChunks": 7,
  "chunkUrls": [
    { "chunkNumber": 1, "presignedUrl": "https://s3.amazonaws.com/...?partNumber=1&uploadId=..." },
    { "chunkNumber": 2, "presignedUrl": "https://s3.amazonaws.com/...?partNumber=2&uploadId=..." }
  ]
}
```

---

### POST /files/{fileId}/complete
**Purpose:** Finalize upload after all chunks are PUT to S3. Backend calls S3 CompleteMultipartUpload.

**Request:**
```json
{
  "chunkEtags": [
    { "chunkNumber": 1, "etag": "\"d41d8cd98f00b204e9800998ecf8427e\"" },
    { "chunkNumber": 2, "etag": "\"7215ee9c7d9dc229d2921a40e899ec5f\"" }
  ],
  "checksum": "sha256-abc123"
}
```

**Response 200:** Full `FileMetadata` JSON with `status: "READY"`.

**Errors:** 403 (wrong owner), 404 (fileId not found), 400 (wrong chunk count).

---

### GET /files/{fileId}/presigned-url
**Purpose:** Get a short-lived download URL. Client downloads directly from S3/CDN.

**Response 200:**
```json
{ "url": "https://d1234.cloudfront.net/files/alice/file-id?Signature=...&Expires=1700000000" }
```

**Errors:** 403 (no access), 404 (not found), 409 (upload incomplete).

---

### POST /files/{fileId}/share
**Purpose:** Grant another user access to a file.

**Request:**
```json
{ "targetUserId": "bob", "permission": "READ" }
```

**Response:** 204 No Content. Publishes a SHARED event to recipient's WebSocket.

---

### DELETE /files/{fileId}/share/{userId}
**Purpose:** Revoke a previously granted share.

**Response:** 204 No Content.

---

### GET /files/changes?since={iso-instant}
**Purpose:** Polling fallback for sync. Returns all changes since `since`.

**Response 200:**
```json
[
  {
    "fileId": "550e8400-...",
    "fileName": "photo.jpg",
    "ownerId": "alice",
    "eventType": "CREATED",
    "fileStatus": "READY",
    "occurredAt": "2024-06-01T12:34:56Z"
  }
]
```

---

## 5. High-Level Architecture

### System Components
```
┌─────────────────────────────────────────────────────────────────┐
│                         CLIENT DEVICES                          │
│  (Desktop app, Mobile app, Web browser)                         │
└───────────┬─────────────────────────────────────────────────────┘
            │ HTTPS / WebSocket
            ▼
┌───────────────────────┐    ┌─────────────────────────────────┐
│    Load Balancer      │    │    CDN (CloudFront)             │
│    (AWS ALB)          │    │    Caches file content at edge  │
└───────────┬───────────┘    └──────────────┬──────────────────┘
            │                               │ signed URL fetch
            ▼                               ▼
┌───────────────────────┐    ┌─────────────────────────────────┐
│  Application Server   │◄──►│    Amazon S3                    │
│  Spring Boot          │    │    (durable object store)       │
│  FileController       │    │    11-nine durability           │
│  FileUploadService    │    │    Cross-region replication      │
│  FileDownloadService  │◄───┘                                 │
│  FileSharingService   │    ┌─────────────────────────────────┐
│  FileSyncService      │◄──►│    Redis (Pub/Sub)              │
│  SyncWebSocketHandler │    │    Channel: file-changes:{uid}  │
└───────────┬───────────┘    └─────────────────────────────────┘
            │
            ▼
┌───────────────────────┐
│   PostgreSQL          │
│   file_metadata       │
│   file_chunks         │
│   shared_files        │
└───────────────────────┘
```

### Upload Flow
```
Client                 App Server              S3
  │                        │                   │
  │ POST /files/initiate   │                   │
  │───────────────────────►│                   │
  │                        │ initiateMultipart │
  │                        │──────────────────►│
  │                        │ ◄─ uploadId       │
  │    {fileId, chunkUrls} │                   │
  │◄───────────────────────│                   │
  │                        │                   │
  │ PUT chunk 1 to S3      │                   │
  │──────────────────────────────────────────►│
  │ ◄─ ETag header          │                   │
  │ PUT chunk 2 to S3      │                   │
  │──────────────────────────────────────────►│
  │ ◄─ ETag header          │                   │
  │                        │                   │
  │ POST /files/{id}/complete (+ ETags)         │
  │───────────────────────►│                   │
  │                        │ completeMultipart │
  │                        │──────────────────►│
  │                        │ ◄─ OK             │
  │                        │ status=READY      │
  │                        │ publish to Redis  │
  │    {status: READY}     │                   │
  │◄───────────────────────│                   │
```

### Download Flow
```
Client                 App Server              S3/CDN
  │                        │                   │
  │ GET /files/{id}/presigned-url              │
  │───────────────────────►│                   │
  │                        │ generate signed URL│
  │    { url: "https://cdn.../file?sig=..." }  │
  │◄───────────────────────│                   │
  │                        │                   │
  │ GET signed URL                             │
  │──────────────────────────────────────────►│
  │ ◄─ file bytes (served from CDN edge)       │
```

### Sync Flow
```
Client A               Redis              Client B (connected)
  │                      │                      │
  │ complete upload       │                      │
  │ →publishChange()      │                      │
  │──────────────────────►│                      │
  │                      │ file-changes:{uid}    │
  │                      │──────────────────────►│
  │                      │                      │ WebSocket push
  │                      │                      │ {CREATED event}
  │                      │                      │
  │                Client C (reconnecting)       │
  │                      │ GET /files/changes?since=T
  │                      │◄─────────────────────────
  │                      │ [list of missed events]
  │                      │──────────────────────────►
```

---

## 6. Deep Dive: File Chunking & Multipart Upload

### Why 8 MB Chunks?

| Chunk Size | Chunks for 50 GB | Notes |
|---|---|---|
| 5 MB (S3 minimum) | 10,240 | Most chunks, slower reconnect math |
| **8 MB** | **6,400** | **Good balance of parallelism vs overhead** |
| 100 MB | 512 | Few chunks, can't parallelize well |

- **S3 minimum part size is 5 MB** (except the last part). Chosen 8 MB for headroom.
- **S3 maximum 10,000 parts per upload** → 8 MB × 10,000 = 80 GB max, covers 50 GB requirement.
- 8 MB matches typical CDN cache line sizes and allows parallel uploads of 3–6 chunks simultaneously on most connections.

### S3 Multipart Upload API Flow
```
1. InitiateMultipartUpload(bucket, key) → uploadId
2. UploadPart(bucket, key, uploadId, partNumber=1..N, bytes) → ETag
3. CompleteMultipartUpload(bucket, key, uploadId, [(1,ETag1), (2,ETag2)...])
   → S3 assembles parts atomically, object becomes visible
```

If upload is aborted: `AbortMultipartUpload(bucket, key, uploadId)` frees stored parts.

### Resumability
If the upload fails mid-chunk:
1. Client still has the `fileId` and `s3UploadId` from initiate response.
2. Client can re-request presigned URLs for only the failed chunks (backend reads `FileChunk.uploaded` to identify missing ones — a natural extension of this implementation).
3. Re-upload failed chunks; call `/complete` with all ETags.

### Deduplication via SHA-256
- Client computes SHA-256 of each chunk before upload.
- SHA-256 is stored in `FileChunk.checksum`.
- Future extension: content-addressable storage — if `checksum` already exists in S3, skip the upload and re-use the existing object. This is "delta sync" — Dropbox's most significant bandwidth optimization.

---

## 7. Deep Dive: Presigned URLs

### Why Direct-to-S3 (Not Through App Server)?

| Approach | Throughput | Scalability | Latency |
|---|---|---|---|
| Client → App Server → S3 | App server bottleneck | Cannot scale horizontally for large files | High (double hop) |
| Client → S3 (presigned URL) | Unlimited (S3 direct) | Infinite | Low |
| Client → S3 (multipart, chunked) | Parallel parts | Parallel to S3 | Lowest |

A 50 GB file at 1 Gbps takes 400 seconds. Routing through the app server would monopolize 400s of a server's bandwidth per upload. With presigned URLs, the app server only handles the short `/initiate` and `/complete` requests.

### Security Properties of Presigned URLs

1. **Time-limited:** Default 5-minute expiry. A leaked URL expires before it can be exploited at scale.
2. **Scope-limited:** The upload URL is scoped to a specific `partNumber` and `uploadId`. It cannot be used to overwrite other objects.
3. **HTTPS-only:** S3 rejects HTTP requests to presigned URLs by policy.
4. **No IAM credentials exposed:** The client never sees AWS keys. Only the backend holds credentials; the presigned URL embeds a HMAC signature that proves authorization.

### CDN (CloudFront) for Downloads
In production, replace the presigned S3 GET URL with a CloudFront signed URL:
- File is served from the nearest PoP (Points of Presence) — Dropbox has ~500 globally.
- S3 origin is read only when the edge cache misses.
- Signed URL uses an RSA key pair stored in AWS Parameter Store — the same interface (`generatePresignedDownloadUrl` returns a String) requires only a single implementation swap.

---

## 8. Deep Dive: Sync Protocol

### WebSocket (Real-Time Path)
```
Client connects: ws://api/ws/sync?userId=alice
Server: registers Redis subscriber on "file-changes:alice"
File upload completes → Redis.publish("file-changes:alice", eventJson)
SyncWebSocketHandler.onMessage() → session.sendMessage(eventJson)
Client receives: { fileId, fileName, eventType: "CREATED", ... }
Client: updates local file list without polling
```

**Why not SSE?** WebSocket is bidirectional (future use for conflict signals). SSE is simpler but unidirectional.

**Why not STOMP over WebSocket?** STOMP requires a message broker integration layer. Raw `TextWebSocketHandler` is easier to explain in an interview and has less moving parts.

### Polling Fallback (Reconnect Path)
After reconnecting, the client computes:
```
GET /files/changes?since={lastKnownSyncTimestamp}
```
The server queries:
1. `file_metadata` where `owner_id = userId AND updated_at > since AND status = READY`
2. `shared_files` for all fileIds shared with this user, then the same query on those fileIds

This returns the full set of missed events in one round trip.

### Multiple Devices
- Each device opens its own WebSocket session.
- In this reference implementation: one session per userId (simplification).
- Production: `Map<String, List<WebSocketSession>>` → all devices receive the same event.
- Redis pub/sub ensures events generated on any app server instance reach all connected devices, regardless of which instance they're connected to.

### Conflict Resolution: Last-Write-Wins
Dropbox uses last-write-wins for binary files (source code editors get copy-conflict files). Rationale:
- Binary files (photos, videos, archives) cannot be three-way merged.
- `updatedAt` timestamp on `FileMetadata` determines the winner.
- The client that writes last gets its version stored; older versions are overwritten.
- A conflict copy (appending the device name to the filename) can be created as a UX hint — not implemented in this reference.

---

## 9. Deep Dive: Storage Architecture

### S3 (Object Storage) vs Block/File Storage

| Storage Type | Examples | Characteristics |
|---|---|---|
| Object storage | S3, GCS, Azure Blob | Flat namespace, HTTP API, eventual consistency, 11-nine durability |
| Block storage | EBS, SAN | Low-latency random I/O, attached to single instance |
| File storage | EFS, NFS | Hierarchical, POSIX, multi-instance mount |

**S3 is the right choice because:**
- Files are immutable after upload (no random writes needed).
- HTTP PUT/GET aligns perfectly with presigned URL flow.
- 11-nine durability (3 AZ replication) without any configuration.
- Native multipart upload API (exactly what we need for 50 GB files).
- Lifecycle policies for automatic archival to Glacier.

### S3 Key Design
Pattern: `files/{ownerId}/{fileId}`

- `ownerId` prefix enables efficient listing of a user's files if S3 Select is used.
- `fileId` is random UUID — prevents hot-spotting on S3 partition keys (S3 partitions by key prefix hash, so random UUIDs spread load evenly).
- S3 key is distinct from the filename — renames only update `file_metadata.file_name`, not the S3 key.

### CDN (CloudFront) Topology
```
User (Tokyo) → CloudFront PoP (Tokyo) → cache HIT → instant
                                       → cache MISS → S3 (us-east-1) → cache fill
```
- Popular files (team-shared documents) are cached at 400+ edge locations.
- Cache invalidation on delete: `CloudFront.createInvalidation("/files/ownerId/fileId")`.

---

## 10. Deep Dive: Availability & Consistency

### Availability Targets
| Component | Strategy | Target |
|---|---|---|
| App servers | Multiple instances behind ALB; stateless | 99.99% |
| PostgreSQL | Multi-AZ RDS with read replicas | 99.95% |
| Redis | ElastiCache cluster mode with replicas | 99.99% |
| S3 | Built-in 11-nine durability, 3-AZ | 99.99% |

### Eventual Consistency Model
The system is designed for **AP (Availability + Partition Tolerance)** per CAP theorem:

1. **Upload completion race:** If two devices upload the same logical file simultaneously, both get separate `fileId` UUIDs. Last `updatedAt` timestamp wins in the UI.
2. **Sync propagation delay:** After `completeUpload`, Redis publishes an event. If a device is offline, it catches up via polling on reconnect. Typical propagation: < 1 second. Worst case (Redis lag): a few seconds.
3. **Read-your-writes:** After calling `/complete`, the same client can immediately call `/presigned-url` — the `READY` status is committed to PostgreSQL before the response returns.
4. **Cross-device delay:** Other devices may see a newly uploaded file 1–5 seconds after the upload completes (WebSocket push latency). This is Dropbox's "eventual consistency" behavior.

### Metadata Database HA
```
Primary RDS (us-east-1a)
    │
    ├── Standby RDS (us-east-1b)  ← automatic failover in 30s (Multi-AZ)
    │
    └── Read Replica (us-east-1c) ← sync queries, reduces primary load
```

### Redis HA
```
Primary Redis (ElastiCache)
    │
    └── Read Replica ← pub/sub continues during primary failover
```
If Redis goes down entirely: WebSocket pushes fail silently (error logged). Clients fall back to polling. The system degrades gracefully.

### S3 Replication
For disaster recovery:
- Enable S3 Cross-Region Replication (CRR) to a second bucket in `eu-west-1`.
- RPO (Recovery Point Objective): < 15 minutes (S3 replication SLA).
- RTO (Recovery Time Objective): < 1 hour (update app config to point to DR bucket).

---

## 11. Trade-offs & Alternatives

### Trade-off 1: Direct-to-S3 vs Streaming Through Service

| | Direct-to-S3 (Chosen) | Through Service |
|---|---|---|
| **Pros** | No server bandwidth used; infinite scale; S3 transfer acceleration | Can log/scan content; simpler auth model |
| **Cons** | Client must handle presigned URL logic; harder to do virus scanning | Server becomes bandwidth bottleneck for 50 GB files |
| **Verdict** | Direct-to-S3 is necessary at 50 GB. Virus scanning can be done async via S3 event → Lambda. |

### Trade-off 2: WebSocket vs Long Polling vs SSE

| | WebSocket (Chosen) | Long Polling | SSE |
|---|---|---|---|
| **Real-time** | Yes (push) | Near-real-time | Yes (push) |
| **Bidirectional** | Yes | No | No |
| **Load balancer** | Sticky session or shared broker | Stateless | Stateless |
| **Complexity** | Medium | Low | Low |
| **Verdict** | WebSocket + Redis pub/sub enables true push without sticky sessions. |

### Trade-off 3: PostgreSQL vs NoSQL for Metadata

| | PostgreSQL (Chosen) | DynamoDB |
|---|---|---|
| **Relational queries** | Native SQL, JOINs, composite indexes | GSIs required; JOINs not supported |
| **ACID transactions** | Full ACID | Single-item only |
| **Scale** | Vertical + read replicas (sufficient at 10 TB metadata) | Unlimited horizontal scale |
| **Operational** | Managed RDS | Fully serverless |
| **Verdict** | For metadata at this scale, PostgreSQL ACID transactions are valuable for upload completion atomicity. Switch to DynamoDB if metadata exceeds 10 TB. |

### Trade-off 4: S3 vs Distributed File System (HDFS/Ceph)

| | S3 (Chosen) | Self-hosted (Ceph/HDFS) |
|---|---|---|
| **Ops overhead** | Zero (managed) | High (dedicated team) |
| **Durability** | 11 nines | Depends on replication factor |
| **Cost** | Pay per GB | Capex + ops labor |
| **Latency** | ~50ms first byte (CDN: <5ms cached) | ~1ms local DC |
| **Verdict** | S3 is correct for a product company. Self-hosted only makes sense at Dropbox's actual scale (>1 EB, where S3 costs become prohibitive). |

### Trade-off 5: Chunk Size (8 MB vs Alternatives)
- **5 MB (S3 minimum):** More chunks, more presigned URL round trips, but finer-grained resume.
- **8 MB (chosen):** Good parallelism (upload 3–6 chunks concurrently), 50 GB = 6,400 parts (well under S3's 10,000 limit).
- **100 MB:** Fewer round trips but poor parallelism and a failed chunk wastes more bandwidth on retry.
