# LeetCode Platform - Architecture Diagram

## System Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              CLIENT LAYER                                │
│                                                                           │
│  ┌──────────┐      ┌──────────┐      ┌──────────┐      ┌──────────┐   │
│  │  Browse  │      │  Submit  │      │   Poll   │      │Leaderboard│   │
│  │ Problems │      │   Code   │      │  Status  │      │   View    │   │
│  └────┬─────┘      └────┬─────┘      └────┬─────┘      └────┬──────┘   │
│       │                 │                   │                 │          │
└───────┼─────────────────┼───────────────────┼─────────────────┼──────────┘
        │                 │                   │                 │
        └─────────────────┴───────────────────┴─────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                              API LAYER                                   │
│                         (Spring Boot - Stateless)                        │
│                                                                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐│
│  │   Problem    │  │  Submission  │  │ Leaderboard  │  │ Competition ││
│  │  Controller  │  │  Controller  │  │  Controller  │  │  Controller ││
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬──────┘│
│         │                 │                   │                 │       │
│  ┌──────▼───────┐  ┌──────▼───────┐  ┌──────▼───────┐  ┌──────▼──────┐│
│  │   Problem    │  │  Submission  │  │ Leaderboard  │  │ Competition ││
│  │   Service    │  │   Service    │  │   Service    │  │   Service   ││
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬──────┘│
│         │                 │                   │                 │       │
└─────────┼─────────────────┼───────────────────┼─────────────────┼───────┘
          │                 │                   │                 │
          │                 └───────┬───────────┘                 │
          │                         │                             │
          ▼                         ▼                             ▼
┌──────────────────┐    ┌───────────────────────┐    ┌──────────────────┐
│  DATABASE LAYER  │    │   ASYNC WORKER POOL   │    │   CACHE LAYER    │
│   (H2/JPA)       │    │  (Spring @Async)      │    │    (Redis)       │
│                  │    │                       │    │                  │
│ ┌──────────────┐ │    │ ┌───────────────────┐│    │ ┌──────────────┐ │
│ │   Problems   │ │    │ │ Thread Pool Exec  ││    │ │ Leaderboard  │ │
│ │  Test Cases  │ │    │ │  Core: 10 threads ││    │ │  ZSET Cache  │ │
│ │  Submissions │ │    │ │  Max:  50 threads ││    │ │              │ │
│ │ Competitions │ │    │ │  Queue: 500       ││    │ │ User Data    │ │
│ └──────────────┘ │    │ └─────────┬─────────┘│    │ │  Hash Cache  │ │
│                  │    │           │          │    │ └──────────────┘ │
└──────────────────┘    └───────────┼──────────┘    └──────────────────┘
                                    │
                                    ▼
                        ┌───────────────────────┐
                        │  CODE EXECUTION LAYER │
                        │   (Docker Containers) │
                        │                       │
                        │ ┌───────────────────┐ │
                        │ │ CodeExecution     │ │
                        │ │    Service        │ │
                        │ └─────────┬─────────┘ │
                        │           │           │
                        │           ▼           │
                        │ ┌───────────────────┐ │
                        │ │ Docker Container  │ │
                        │ │                   │ │
                        │ │ • openjdk:17-slim │ │
                        │ │ • python:3.11-slim│ │
                        │ │ • node:20-slim    │ │
                        │ │ • gcc:latest      │ │
                        │ │ • golang:1.21     │ │
                        │ │                   │ │
                        │ │ Security:         │ │
                        │ │ ✓ Read-only FS    │ │
                        │ │ ✓ No network      │ │
                        │ │ ✓ 256MB RAM limit │ │
                        │ │ ✓ 50% CPU quota   │ │
                        │ │ ✓ 5s timeout      │ │
                        │ └───────────────────┘ │
                        └───────────────────────┘
```

## Request Flow Diagrams

### 1. Submit Code Flow

```
User                API Server              Worker Thread           Docker              Redis
 │                      │                        │                    │                   │
 │  POST /submit       │                        │                    │                   │
 ├────────────────────>│                        │                    │                   │
 │                     │                        │                    │                   │
 │                     │ Save(QUEUED)           │                    │                   │
 │                     ├──────────────> DB      │                    │                   │
 │                     │                        │                    │                   │
 │                     │ processAsync()         │                    │                   │
 │                     ├───────────────────────>│                    │                   │
 │                     │                        │                    │                   │
 │  202 Accepted       │                        │                    │                   │
 │<────────────────────┤                        │                    │                   │
 │  {submissionId}     │                        │                    │                   │
 │                     │                        │                    │                   │
 │                     │                        │ Update(PROCESSING) │                   │
 │                     │                        ├─────────> DB       │                   │
 │                     │                        │                    │                   │
 │                     │                        │ Create Container   │                   │
 │                     │                        ├───────────────────>│                   │
 │                     │                        │                    │                   │
 │                     │                        │  Execute Code      │                   │
 │                     │                        │  (5s timeout)      │                   │
 │                     │                        │<───────────────────┤                   │
 │                     │                        │                    │                   │
 │                     │                        │ Update(COMPLETED)  │                   │
 │                     │                        ├─────────> DB       │                   │
 │                     │                        │  result: ACCEPTED  │                   │
 │                     │                        │                    │                   │
 │                     │                        │ updateLeaderboard()│                   │
 │                     │                        ├───────────────────────────────────────>│
 │                     │                        │                    │   ZADD +score     │
 │                     │                        │                    │   HSET metadata   │
 │                     │                        │<───────────────────────────────────────┤
 │                     │                        │                    │                   │
 │  GET /status        │                        │                    │                   │
 ├────────────────────>│                        │                    │                   │
 │                     │                        │                    │                   │
 │  200 OK             │                        │                    │                   │
 │<────────────────────┤                        │                    │                   │
 │  {result: ACCEPTED} │                        │                    │                   │
```

### 2. Leaderboard Query Flow

```
User                API Server              Redis
 │                      │                     │
 │  GET /leaderboard   │                     │
 ├────────────────────>│                     │
 │                     │                     │
 │                     │ ZRANGE 0 99 REV     │
 │                     │ WITHSCORES          │
 │                     ├────────────────────>│
 │                     │                     │
 │                     │ [user1: -4996400,   │
 │                     │  user2: -2997300,   │
 │                     │  user3: -1998200]   │
 │                     │<────────────────────┤
 │                     │                     │
 │                     │ HGETALL user data   │
 │                     ├────────────────────>│
 │                     │ (for each user)     │
 │                     │<────────────────────┤
 │                     │                     │
 │  200 OK             │                     │
 │<────────────────────┤                     │
 │  [{rank:1, ...},    │                     │
 │   {rank:2, ...}]    │                     │
```

### 3. Code Execution Security Model

```
┌────────────────────────────────────────────────────────────┐
│                    Docker Container                        │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐ │
│  │              Read-Only Root Filesystem                │ │
│  │                                                        │ │
│  │  /usr                  /bin                    /lib   │ │
│  │   ├─ bin/              ├─ sh                   ├─...  │ │
│  │   ├─ lib/              ├─ ls                   └─...  │ │
│  │   └─ ...               └─ ...                         │ │
│  │                                                        │ │
│  │  /workspace (bind mount from host /tmp - WRITABLE)    │ │
│  │   ├─ Solution.java                                    │ │
│  │   ├─ Solution.class (after compile)                   │ │
│  │   └─ output.txt (execution results)                   │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                             │
│  Security Constraints:                                      │
│  ┌──────────────────────────────────────────────────────┐ │
│  │ • Memory Limit:      256 MB (hard limit)             │ │
│  │ • CPU Quota:         50% of one core                 │ │
│  │ • Network Mode:      NONE (no eth0 interface)        │ │
│  │ • Execution Timeout: 5 seconds (enforced by host)    │ │
│  │ • System Calls:      Restricted via seccomp          │ │
│  │ • Filesystem:        Read-only (except /workspace)   │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                             │
│  Container Lifecycle:                                       │
│  1. Create container with constraints                       │
│  2. Start container                                         │
│  3. Execute code (javac + java / python / node)            │
│  4. Capture stdout/stderr                                   │
│  5. Wait up to 5 seconds                                    │
│  6. Stop container (kill if necessary)                      │
│  7. Remove container                                        │
│  8. Clean up temp directory                                 │
└────────────────────────────────────────────────────────────┘
```

## Data Flow Patterns

### Async Processing Pattern

```
┌───────────────────────────────────────────────────────────────┐
│                    Submission Lifecycle                        │
└───────────────────────────────────────────────────────────────┘

QUEUED ─────> PROCESSING ─────> COMPLETED ────> Leaderboard Update
  │                                 │
  │                                 ├─> result: ACCEPTED
  │                                 ├─> result: WRONG_ANSWER
  │                                 ├─> result: TIME_LIMIT_EXCEEDED
  │                                 └─> result: RUNTIME_ERROR
  │
  └─────────> FAILED (system error, retry possible)


Thread Pool Behavior:
┌──────────────────────────────────────────────────────────────┐
│  Requests: 1-10  → Use core threads (immediate execution)    │
│  Requests: 11-510 → Queue up (core threads busy)             │
│  Requests: 511+   → Create new threads (up to max 50)        │
│  Requests: 560+   → Reject with exception                    │
└──────────────────────────────────────────────────────────────┘
```

### Leaderboard Scoring

```
┌───────────────────────────────────────────────────────────────┐
│                   Score Calculation                            │
└───────────────────────────────────────────────────────────────┘

score = problemsSolved × 1,000,000 - (totalTimeMs ÷ 1,000)

Examples:
  10 problems in 90 minutes (5,400,000 ms)
  = 10 × 1,000,000 - 5,400
  = 9,994,600

  5 problems in 30 minutes (1,800,000 ms)
  = 5 × 1,000,000 - 1,800
  = 4,998,200

  3 problems in 10 minutes (600,000 ms)
  = 3 × 1,000,000 - 600
  = 2,999,400

Stored in Redis as NEGATIVE for ascending rank order:
  Rank 1: user1 → -9,994,600
  Rank 2: user2 → -4,998,200
  Rank 3: user3 → -2,999,400

ZRANGE returns ascending: [-9994600, -4998200, -2999400]
Which maps to:      [Rank 1,    Rank 2,    Rank 3]
```

## Scaling Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                    Production Deployment                        │
└────────────────────────────────────────────────────────────────┘

                        ┌─────────────────┐
                        │  Load Balancer  │
                        │   (ALB/NGINX)   │
                        └────────┬────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                  │
              ▼                  ▼                  ▼
      ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
      │ API Server 1 │   │ API Server 2 │   │ API Server N │
      │  (Stateless) │   │  (Stateless) │   │  (Stateless) │
      └──────┬───────┘   └──────┬───────┘   └──────┬───────┘
             │                  │                  │
             └──────────────────┼──────────────────┘
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                 │
              ▼                 ▼                 ▼
    ┌─────────────────┐ ┌──────────────┐ ┌──────────────┐
    │   PostgreSQL    │ │ Redis Cluster│ │ Docker Hosts │
    │                 │ │              │ │              │
    │ Master          │ │ Shard 1      │ │ Host 1       │
    │ ├─ Read Replica │ │ Shard 2      │ │ Host 2       │
    │ └─ Read Replica │ │ Shard 3      │ │ Host N       │
    └─────────────────┘ └──────────────┘ └──────────────┘

Auto-Scaling Triggers:
┌──────────────────────────────────────────────────────────────┐
│ API Servers:                                                  │
│   ✓ CPU > 70%                → Scale up                       │
│   ✓ Request rate > 10K/s     → Scale up                       │
│   ✓ CPU < 30% for 10 min     → Scale down                     │
│                                                                │
│ Docker Hosts:                                                 │
│   ✓ Container count > 80%    → Add host                       │
│   ✓ CPU utilization > 80%    → Add host                       │
│   ✓ Low utilization 10 min   → Remove host                    │
│                                                                │
│ Redis Cluster:                                                │
│   ✓ Memory > 80%             → Add shard                      │
│   ✓ Operations > 100K/s      → Add replica                    │
└──────────────────────────────────────────────────────────────┘
```

## Technology Stack Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                        Component Matrix                          │
├─────────────────┬───────────────────────────────────────────────┤
│ Framework       │ Spring Boot 3.2.0                             │
│ Language        │ Java 17                                       │
│ Database        │ H2 (dev) / PostgreSQL (prod)                 │
│ ORM             │ Spring Data JPA + Hibernate                   │
│ Cache           │ Redis 7 + Spring Data Redis                   │
│ Async           │ Spring @Async + ThreadPoolTaskExecutor        │
│ Code Execution  │ Docker Java API 3.3.4                         │
│ Containerization│ Docker Engine (openjdk, python, node, gcc, go)│
│ Build Tool      │ Maven 3.8+                                    │
│ Testing         │ JUnit 5, Spring Boot Test                     │
└─────────────────┴───────────────────────────────────────────────┘
```

## Key Performance Numbers

```
┌─────────────────────────────────────────────────────────────────┐
│                     Capacity Planning                            │
├──────────────────────────────────────────────────────────────────┤
│ Concurrent Users:        100,000 (during competitions)           │
│ Concurrent Submissions:  10,000                                  │
│ Submission Latency:      < 5 seconds (P99)                       │
│ Leaderboard Updates:     O(log N) per submission                 │
│ Leaderboard Queries:     O(log N + K) where K = page size        │
│ CPU Cores Required:      ~1,667 for 10K submissions in 1 minute  │
│ Memory per Container:    256 MB                                  │
│ Database Storage:        ~11 GB/day (growing)                    │
│ Redis Memory:            ~100 MB per 100K user leaderboard       │
│ API Response Time:       < 100ms (excluding code execution)      │
└──────────────────────────────────────────────────────────────────┘
```
