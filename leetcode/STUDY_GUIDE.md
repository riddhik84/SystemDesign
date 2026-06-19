# LeetCode System Design - Interview Study Guide

## 📚 Problem Overview

**Question:** Design a LeetCode-like online coding platform that supports problem browsing, code execution, competitions, and real-time leaderboards.

**Difficulty:** Senior Level (L5-L6)

**Interview Duration:** 45-60 minutes

## 🎯 Key Requirements to Remember

### Functional Requirements (5 minutes)
1. **Problem Catalog** - Paginated list with filtering by difficulty/tags
2. **Code Execution** - Submit solutions and get instant feedback (<5 seconds)
3. **Competitions** - 90-minute contests with up to 10 problems
4. **Live Leaderboard** - Real-time rankings during active competitions

### Non-Functional Requirements (3 minutes)
1. **Scale**: 100,000 concurrent users during competitions
2. **Performance**: 5-second submission results
3. **Security**: Sandboxed execution for untrusted code
4. **Availability**: Prefer availability over consistency
5. **Throughput**: Handle 10,000 concurrent submissions

## 🔢 Capacity Estimation (5 minutes)

### Peak Load Calculations
```
Concurrent Submissions: 10,000
Test Cases per Problem: 100
Execution Time per Test: 100ms
Total Execution Time: 10,000ms (10 seconds) per submission

CPU Cores Needed:
  10,000 submissions/minute × 10 seconds/submission
  ÷ 60 seconds/minute
  = ~1,667 CPU cores for 1-minute processing
```

### Storage Estimates
```
Problems: 10,000 problems × 50KB = 500MB
Submissions: 1M submissions/day × 10KB = 10GB/day
Users: 1M users × 1KB = 1GB
Total: ~11GB/day growing storage
```

## 🏗️ High-Level Architecture (10 minutes)

### Component Diagram
```
Client → API Server → Database (Problems, Submissions)
              ↓
         Message Queue (SQS)
              ↓
         Worker Pool → Docker Containers (Code Execution)
              ↓
         Redis (Leaderboard Cache)
```

### Key Components
1. **API Server**: Stateless REST API (Spring Boot)
2. **Database**: SQL database for problems/submissions (PostgreSQL/MySQL)
3. **Message Queue**: Buffer submissions (AWS SQS/RabbitMQ)
4. **Worker Pool**: Async code execution workers
5. **Docker Engine**: Isolated code execution containers
6. **Redis**: Real-time leaderboard (Sorted Sets)

## 💡 Critical Design Decisions (15 minutes)

### 1. Code Execution Strategy ⭐⭐⭐

**Options Considered:**
- ❌ **Run on API server**: Insecure, no isolation
- ❌ **Virtual Machines**: Too slow to start, resource-intensive
- ✅ **Docker Containers**: Best balance of speed, isolation, and security
- ⚠️ **AWS Lambda**: Viable but cold start latency concerns

**Chosen: Docker Containers**

**Security Measures:**
```yaml
- Read-only filesystem (except /tmp)
- CPU limit: 50% of one core
- Memory limit: 256MB
- Network: Disabled
- Timeout: 5 seconds hard limit
- System calls: Restricted via seccomp
```

**Interview Talking Points:**
- "We need strong isolation because we're running untrusted code"
- "Docker provides process isolation with shared kernel benefits"
- "VMs would take 30-60 seconds to start; Docker containers start in <1 second"
- "We enforce resource limits to prevent abuse"

### 2. Leaderboard Implementation ⭐⭐⭐

**Options Considered:**
- ❌ **Database queries**: Too slow for real-time updates
- ❌ **In-memory sorting**: Doesn't scale across servers
- ✅ **Redis Sorted Set (ZSET)**: O(log N) operations, built-in ranking

**Implementation:**
```redis
Key: competition:leaderboard:{competitionId}
Score: -(problemsSolved × 1,000,000 - completionTimeMs ÷ 1,000)
Member: userId

Commands:
  ZADD competition:leaderboard:123 -2000500.0 user456
  ZRANGE competition:leaderboard:123 0 99 REV WITHSCORES
```

**Score Calculation:**
```
score = problemsSolved × 1,000,000 - (totalTimeMs ÷ 1,000)

Example:
  5 problems solved in 3,600,000ms (60 minutes)
  = 5 × 1,000,000 - 3,600
  = 4,996,400

Stored as negative: -4,996,400 (for ascending rank order)
```

**Interview Talking Points:**
- "Redis ZSET provides O(log N) insertions and range queries"
- "Negative scores allow us to use ZRANGE for descending order"
- "We multiply problems by 1M to prioritize count over time"
- "Can handle 100K concurrent updates with Redis Cluster"

### 3. Async Processing Architecture ⭐⭐

**Flow:**
```
1. User submits → API returns 202 Accepted with submissionId
2. API writes to submission queue (SQS)
3. Worker pulls from queue
4. Worker executes code in Docker container
5. Worker updates database and Redis cache
6. Client polls GET /submissions/{id} every 1 second
```

**Why Async?**
- Prevents API server from blocking 5+ seconds per submission
- Enables horizontal scaling of workers independently
- Provides natural retry mechanism via queue
- Better resource utilization

**Interview Talking Points:**
- "Code execution can take up to 5 seconds; we can't block API threads"
- "Queue allows workers to scale independently from API servers"
- "If a worker crashes, the message returns to queue for retry"
- "Polling is acceptable for this use case; WebSockets would be over-engineering"

### 4. Horizontal Scaling Strategy ⭐⭐

**Auto-Scaling Triggers:**
```
API Servers:
  - CPU > 70% → scale up
  - Queue depth > 1000 → scale up
  
Worker Pool:
  - Queue depth > 100 → scale up
  - CPU > 80% → scale up
  - Scale down after 10 minutes of low utilization
```

**Load Distribution:**
- API servers behind load balancer (ALB/NGINX)
- Workers pull from shared queue (natural load balancing)
- Redis Cluster for leaderboard sharding by competitionId
- Database read replicas for problem catalog queries

## 📊 Data Model (5 minutes)

### Core Entities

**Problem**
```sql
CREATE TABLE problems (
  id UUID PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  question TEXT NOT NULL,
  level ENUM('EASY', 'MEDIUM', 'HARD'),
  tags TEXT[],
  code_stubs JSONB,
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE test_cases (
  id UUID PRIMARY KEY,
  problem_id UUID REFERENCES problems(id),
  type ENUM('SAMPLE', 'HIDDEN', 'EDGE_CASE'),
  input TEXT,
  expected_output TEXT
);
```

**Submission**
```sql
CREATE TABLE submissions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  problem_id UUID REFERENCES problems(id),
  competition_id UUID,
  code TEXT NOT NULL,
  language VARCHAR(50),
  status ENUM('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'),
  result ENUM('ACCEPTED', 'WRONG_ANSWER', 'TLE', 'RUNTIME_ERROR'),
  test_cases_passed INT,
  total_test_cases INT,
  execution_time_ms BIGINT,
  submitted_at TIMESTAMP DEFAULT NOW(),
  completed_at TIMESTAMP,
  INDEX idx_user_problem (user_id, problem_id),
  INDEX idx_competition (competition_id)
);
```

## 🔌 API Design (5 minutes)

### Critical Endpoints

```http
# Problem Browsing
GET /api/problems?page=1&limit=100&difficulty=MEDIUM&tags=array,dp

# Problem Details with Language-Specific Stub
GET /api/problems/{id}?language=java

# Submit Solution (Async)
POST /api/problems/{id}/submit
{
  "userId": "user123",
  "competitionId": "comp456",  // optional
  "code": "public int[] twoSum(...) { ... }",
  "language": "java"
}
Response: 202 Accepted
{
  "submissionId": "sub789",
  "status": "QUEUED"
}

# Poll Submission Status
GET /api/submissions/{id}
Response:
{
  "id": "sub789",
  "status": "COMPLETED",
  "result": "ACCEPTED",
  "testCasesPassed": 100,
  "totalTestCases": 100,
  "executionTimeMs": 1523
}

# Leaderboard (Cached in Redis)
GET /api/leaderboard/{competitionId}?page=1&limit=100
```

## 🎤 Interview Talking Track

### Opening (2 minutes)
"I'll design a LeetCode-like platform supporting code execution, competitions, and leaderboards for 100K concurrent users. Let me start by clarifying requirements..."

### Requirements Phase (5 minutes)
- Confirm: problem browsing, code execution, competitions, leaderboards
- Clarify: languages supported? (Java, Python, JavaScript, C++, Go)
- Confirm: 5-second execution timeout, 100K concurrent users
- Out of scope: user auth, payments, discussions, analytics

### Capacity Estimation (5 minutes)
"For 10,000 concurrent submissions with 100 test cases each at 100ms per test, we need approximately 1,667 CPU cores to process within 1 minute..."

### High-Level Design (10 minutes)
"I'll use a queue-based architecture with async workers for code execution. API servers handle requests, workers execute code in Docker containers, and Redis powers real-time leaderboards..."

### Deep Dives (20 minutes)
**Interviewer likely asks:**
1. **"How do you ensure code execution security?"**
   - Docker containers with read-only filesystem
   - No network access, CPU/memory limits
   - 5-second timeout, restricted system calls
   
2. **"How does the leaderboard stay real-time?"**
   - Redis Sorted Set for O(log N) updates
   - Score formula prioritizes problems solved over time
   - Client polls every 5 seconds
   
3. **"How do you handle 10K concurrent submissions?"**
   - Async processing with message queue
   - Auto-scaling worker pool
   - Horizontal scaling of API servers

4. **"What if a Docker container hangs?"**
   - Hard 5-second timeout enforced
   - Worker monitoring with health checks
   - Dead letter queue for failed submissions

### Closing (3 minutes)
"To summarize: we use async processing with Docker containers for secure code execution, Redis for real-time leaderboards, and horizontal scaling to handle 100K users..."

## ⚠️ Common Pitfalls to Avoid

1. ❌ **Running code directly on API server** - Major security risk
2. ❌ **Synchronous code execution** - Blocks API threads, doesn't scale
3. ❌ **Database for leaderboard** - Too slow for real-time updates
4. ❌ **WebSockets for status updates** - Over-engineered for this use case
5. ❌ **No resource limits on containers** - DDoS vulnerability
6. ❌ **Forgetting test case serialization** - Each language needs harness

## 🎯 Senior-Level Talking Points

### Show Depth
- "We'll use seccomp profiles to restrict system calls in containers"
- "Redis Cluster sharding by competitionId enables horizontal scaling"
- "We can batch test case execution to reduce container startup overhead"

### Trade-offs
- "Docker vs Lambda: Docker gives us sub-second start time, Lambda has cold start issues but better scaling"
- "Polling vs WebSockets: Polling is simpler and sufficient for 5-second intervals"
- "SQL vs NoSQL: SQL for problems (structured, relational), NoSQL could work for submissions (high write volume)"

### Production Readiness
- "Add circuit breakers for Docker engine failures"
- "Implement rate limiting per user (10 submissions/minute)"
- "Monitor queue depth for auto-scaling triggers"
- "Set up alerts for execution timeout rate > 5%"

## 📈 Follow-Up Questions to Expect

1. **"How would you detect plagiarism?"**
   - Code similarity algorithms (Levenshtein distance, AST comparison)
   - Store submission hashes for fast lookups
   - Flag similar submissions within same competition

2. **"How do you handle test case updates?"**
   - Version test cases with timestamps
   - Rerun affected submissions if critical test case added
   - Maintain audit log of test case changes

3. **"What if Redis goes down during a competition?"**
   - Rebuild from database submissions (takes ~30 seconds)
   - Use Redis persistence (RDB snapshots + AOF)
   - Redis Sentinel for automatic failover

4. **"How do you prevent infinite loops?"**
   - Hard timeout enforced by Docker (5 seconds)
   - CPU quota prevents CPU spinning
   - Monitor execution time distribution for abuse

## 🔑 Key Takeaways

1. **Security First**: Docker containers with strict resource limits
2. **Async Processing**: Queue-based architecture for scalability
3. **Redis ZSET**: Perfect data structure for leaderboards
4. **Horizontal Scaling**: Stateless design enables easy replication
5. **Capacity Planning**: ~1,667 CPU cores for 10K concurrent submissions

## ⏱️ Time Management

- Requirements & Clarifications: 5 minutes
- Capacity Estimation: 5 minutes
- High-Level Architecture: 10 minutes
- Deep Dives (2-3 topics): 20 minutes
- Trade-offs & Closing: 5 minutes
- **Total: 45 minutes**

---

## 💪 Practice Strategy

1. **Week 1**: Draw architecture diagram from memory
2. **Week 2**: Explain Docker security measures without notes
3. **Week 3**: Calculate capacity requirements for different scales
4. **Week 4**: Mock interview with focus on leaderboard deep dive
5. **Week 5**: Full end-to-end interview simulation

**Good luck! Remember: clarity > completeness, communication > correctness**
