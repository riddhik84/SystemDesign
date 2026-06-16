# Dropbox System Design — Interview Study Guide

---

## Clarifying Questions to Ask the Interviewer

Before drawing anything, ask these. Each answer changes the design significantly.

| Question | Why it matters |
|---|---|
| **What is the maximum file size?** | 50 GB forces multipart upload. 10 MB allows single-part. |
| **Do we need real-time sync, or is eventual sync (periodic polling) acceptable?** | Real-time requires WebSocket + Redis pub/sub. Polling is simpler. |
| **What is the conflict resolution strategy when two devices edit the same file?** | Last-write-wins is simplest. OT/CRDT is complex but needed for collaborative docs. |
| **Is sharing within an org, or across the internet?** | Affects auth model (SSO vs link-sharing), access-control complexity. |
| **Do we need file versioning / history?** | Requires extra storage, tombstone design, version table. |
| **Read-heavy or write-heavy workload?** | Affects CDN investment, read replica count, cache strategy. |
| **What consistency guarantee do users expect?** | "I just uploaded — can I download from another device immediately?" drives sync design. |
| **What scale are we targeting?** | 100M users vs 1M changes chunk sizing math and infrastructure choices. |

---

## Numbers to Know Cold

| Metric | Value | How to derive |
|---|---|---|
| Total storage (1B users × 10 MB avg) | 10 PB | 10^9 × 10 × 10^6 = 10^16 bytes = 10 PB |
| Upload QPS at 100M DAU, 5 files/day | ~5,800/s | 5 × 10^8 / 86,400 |
| Download QPS | ~11,600/s | ~2× upload rate |
| Chunks per 50 GB file (8 MB chunks) | 6,400 | 50 × 1024 / 8 = 6,400 |
| S3 part limit per upload | 10,000 | Know this cold — sets chunk size floor |
| S3 minimum part size | 5 MB | Except last part |
| Presigned URL expiry | 5 min | Short enough to limit blast radius |
| Typical sync propagation delay | < 1 second | Redis pub/sub latency |
| S3 durability | 11 nines | 99.999999999% — 3-AZ replication |

---

## Core Entities

**3 tables is all you need to explain clearly:**

```
file_metadata — The "what": fileId, name, size, ownerId, status, s3Key
file_chunks   — The "how": partNumber, etag, uploaded flag
shared_files  — The "who": fileId + sharedWithUserId composite PK
```

The `status` lifecycle is a key invariant: `UPLOADING → READY` is a one-way gate. Nothing is downloadable until READY. The status column drives every access check.

---

## API Endpoints (6 total)

| Method | Path | Purpose | Response |
|---|---|---|---|
| POST | /files/initiate | Start chunked upload, get presigned URLs | 201 + fileId + chunkUrls |
| POST | /files/{fileId}/complete | Finalize upload with ETags | 200 + FileMetadata (READY) |
| GET | /files/{fileId}/presigned-url | Get download URL | 200 + { url } |
| POST | /files/{fileId}/share | Grant access to another user | 204 |
| DELETE | /files/{fileId}/share/{userId} | Revoke access | 204 |
| GET | /files/changes?since= | Poll for sync changes | 200 + [events] |

**The pattern:** initiate → upload (direct to S3) → complete. This two-phase design is the most important thing to explain clearly.

---

## The ONE Central Decision: Chunked Upload via Presigned URLs

This is what separates a strong answer from a weak one. Know all three approaches and why you pick the third.

### Approach 1: Upload via App Server (Naive)
```
Client → POST /upload (file bytes in body) → App Server → S3
```
**Problem:** A 50 GB file takes ~400 seconds at 1 Gbps. One upload monopolizes an app server thread and its network bandwidth. At 5,800 uploads/sec this is completely infeasible. Never propose this for large files.

### Approach 2: Single Presigned S3 URL
```
Client → GET /presigned-url → App Server → returns PUT URL
Client → PUT {file} → S3
```
**Better** — removes app server from data path. But:
- S3 single-part uploads are limited to 5 GB.
- A failed upload requires restarting from byte 0.
- No parallelism — one connection, linear throughput.

### Approach 3: Multipart Upload with Presigned Chunk URLs (Chosen)
```
Client → POST /initiate → App Server → S3.InitiateMultipartUpload → uploadId
App Server generates one presigned PUT URL per chunk (up to 10,000)
Client → PUT chunk 1 to S3 → ETag  ─┐
Client → PUT chunk 2 to S3 → ETag  ─┤ (in parallel)
Client → PUT chunk N to S3 → ETag  ─┘
Client → POST /complete + {chunkNumber, ETag}[] → App Server → S3.Complete
S3 assembles parts atomically
```
**Why this wins:**
1. Handles 50 GB (S3 multipart supports up to 5 TB).
2. App server never touches file bytes — infinite scale.
3. Failed chunks are retried individually — resumable.
4. Parallel chunk uploads saturate available bandwidth.
5. The ETag scheme provides integrity verification.

**In the interview, draw this flow on the whiteboard. It shows you understand the constraint (50 GB) and know the right AWS primitive.**

---

## Architecture Diagram (Whiteboard Version)

```
           ┌─────────────────────────────────────────────────────┐
           │                 CLIENT DEVICE                        │
           └──────┬───────────────────────────────┬──────────────┘
                  │ HTTPS API calls                │ WebSocket
                  ▼                                ▼
        ┌─────────────────────────────────────────────┐
        │          Load Balancer (ALB)                │
        └─────────────────┬───────────────────────────┘
                          │
        ┌─────────────────▼───────────────────────────┐
        │       App Server Cluster (Spring Boot)       │
        │                                             │
        │  /files/initiate ──► S3.initiateMultipart  │
        │  /files/complete ──► S3.completeMultipart  │
        │  /files/presigned-url → generate S3 URL     │
        │  /ws/sync ──────── WebSocket sessions       │
        └────┬──────────────────────┬─────────────────┘
             │                      │
             ▼                      ▼
    ┌────────────────┐    ┌──────────────────────┐
    │  PostgreSQL    │    │  Redis Pub/Sub        │
    │  (metadata)    │    │  file-changes:{uid}   │
    └────────────────┘    └──────────────────────┘
                                    │
                          ┌─────────▼─────────┐
                          │  WebSocket push    │
                          │  to all connected  │
                          │  sessions for uid  │
                          └────────────────────┘

    Client → S3 directly (presigned URLs, bypasses app server)
    Client → CloudFront → S3 (download, CDN caches popular files)
```

---

## Common Follow-Up Q&A

### What if the upload fails mid-chunk?
- The client retains the `fileId` and knows which chunks succeeded (from its local state or by calling a status endpoint).
- Re-request presigned URLs for only failed chunks.
- Re-upload failed chunks; call `/complete` with all ETags.
- S3 keeps partial uploads alive for 7 days by default (configurable with lifecycle policy).

### How do you handle conflicts when two devices edit the same file simultaneously?
- **Last-write-wins:** The file with the later `updatedAt` timestamp wins. Simple, deterministic.
- **Conflict copy:** Create a new file named "filename (conflicted copy — device name — date)" for the older version. Dropbox does exactly this.
- **OT/CRDT:** Only needed for text documents (Google Docs model). For binary files, it's not feasible. Know this distinction.

### Why not stream file bytes through your service?
**Memory:** A 50 GB file can't fit in a JVM heap. Streaming would require server-side buffering.
**Bandwidth:** At 5,800 uploads/sec × 50 GB = your entire datacenter's network.
**Latency:** Double-hop (client → app → S3) vs single-hop (client → S3).
**Answer:** "We use presigned URLs to keep the app server out of the data path entirely."

### How do you ensure data integrity end-to-end?
1. Client computes SHA-256 before upload, sends in `/initiate`.
2. S3 part ETags verify each chunk.
3. S3 computes MD5 of assembled object, exposed in object metadata.
4. Application can compare `FileMetadata.checksum` against S3 object checksum on read.

### How would you implement delta sync (only sync changed bytes)?
- Block-level deduplication: split file into fixed blocks, hash each block.
- If block hash already exists in the system (from another file or version), skip that block's upload.
- Store block hashes in a content-addressable store (`file_blocks` table + blob store).
- This is how Dropbox reduced bandwidth by 95% — a key follow-up topic.

### What happens if Redis goes down?
- `FileSyncService.publishChange()` catches the exception and logs it.
- Real-time WebSocket pushes stop — clients don't get instant notifications.
- Clients fall back to polling `GET /files/changes` on reconnect or on a timer.
- The system degrades gracefully; no data is lost (changes are still written to PostgreSQL).

### How do you scale WebSocket connections to millions of users?
- App servers are stateless — WebSocket sessions use in-memory `sessionMap`.
- Redis pub/sub bridges app server instances: any server can publish, any subscriber receives.
- At 100M DAU × 5 devices = 500M concurrent WebSocket connections → need ~500k app server instances with 1000 connections each. In practice, use a dedicated WebSocket layer (AWS API Gateway WebSocket) backed by Redis.

### Why PostgreSQL and not DynamoDB for metadata?
- Metadata is relational: file ↔ chunks ↔ shares.
- ACID transaction for `completeUpload` (update FileMetadata + clear FileChunks + publish event atomically).
- Sync query (`updated_at > since`) maps naturally to a B-tree index, not a GSI scan.
- Switch to DynamoDB when metadata exceeds ~10 TB and horizontal sharding becomes necessary.

---

## What Interviewers Want to Hear

1. **"For files up to 50 GB, we must use S3 multipart upload with presigned URLs — app server can never touch the bytes."** Say this within the first 5 minutes.

2. **"Upload has two phases: initiate (get presigned URLs) and complete (provide ETags). The client uploads directly to S3 between these calls."** Draw the sequence diagram.

3. **"Sync uses WebSocket for real-time push backed by Redis pub/sub. On reconnect, clients poll GET /changes?since=T to catch up."** Two-tier fallback is the right answer.

4. **"Conflict resolution is last-write-wins. For binary files, three-way merge is infeasible."** Shows pragmatism.

5. **"Presigned URLs expire in 5 minutes. This limits the blast radius of a leaked URL."** Shows you think about security.

6. Mention **CDN (CloudFront) for downloads** — popular files served from edge, reduces S3 costs 10x.

7. Mention **chunk size justification** — S3 minimum 5 MB, 10,000 part limit, 8 MB chosen for headroom.

---

## Common Mistakes

| Mistake | Correct Answer |
|---|---|
| Routing file bytes through the app server | Use presigned URLs — app server only handles metadata |
| Not mentioning S3 multipart for large files | InitiateMultipartUpload is the required primitive |
| WebSocket without a pub/sub layer | Redis pub/sub is needed for multi-server deployments |
| Forgetting the polling fallback | Clients need /changes?since= for reconnect recovery |
| "We'll use a database to store file bytes" | Blob storage (S3) is not a database — always use object storage |
| Same-region metadata and storage | Cross-region replication for DR |
| Strong consistency for sync | Eventual consistency is correct and expected |
| Ignoring the ETag → CompleteMultipartUpload link | ETags are required by S3 — must be collected and returned |
| Using a single presigned URL for 50 GB | Single-part S3 upload limit is 5 GB |

---

## One-Page Cheat Sheet

```
PROBLEM: Store and sync files up to 50 GB across devices.

KEY NUMBERS:
  • 10 PB storage (1B users × 10 MB avg)
  • 5,800 upload QPS at scale
  • 8 MB chunk size (S3 min=5MB, max 10k parts)
  • 5-min presigned URL expiry

3 TABLES:
  file_metadata (fileId, ownerId, status, s3Key, s3UploadId, totalChunks)
  file_chunks   (fileId, chunkNumber, etag, uploaded)
  shared_files  (fileId + sharedWithUserId PK, permission)

6 ENDPOINTS:
  POST /files/initiate            → 201 + chunkUrls
  POST /files/{id}/complete       → 200 + READY metadata
  GET  /files/{id}/presigned-url  → 200 + { url }
  POST /files/{id}/share          → 204
  DELETE /files/{id}/share/{uid}  → 204
  GET  /files/changes?since=T     → 200 + [events]

UPLOAD FLOW (3 phases):
  1. /initiate → S3.InitiateMultipart → save FileMetadata(UPLOADING) → return presigned URLs
  2. Client PUT chunks directly to S3 → collect ETags
  3. /complete → S3.CompleteMultipart → FileMetadata.status=READY → Redis.publish

DOWNLOAD FLOW:
  /presigned-url → check access → generatePresignedUrl(5min) → client fetches from CDN

SYNC:
  Real-time: WebSocket + Redis pub/sub "file-changes:{userId}"
  Fallback:  GET /files/changes?since=T (polls file_metadata.updated_at > T)
  Conflict:  Last-write-wins (updatedAt timestamp)

DESIGN CHOICES:
  • S3 for blobs (11-nine durability, multipart API, CDN-native)
  • PostgreSQL for metadata (ACID for upload completion, B-tree for sync queries)
  • Redis pub/sub for fan-out (stateless app servers, cross-instance push)
  • Eventual consistency (AP system — availability > consistency)
  • CDN (CloudFront) for download (edge caching, reduce S3 egress)
```
