# LeetCode Online Coding Platform - System Design

A scalable implementation of a LeetCode-like online coding platform supporting code execution, competitions, and real-time leaderboards. Built with Spring Boot to handle 100,000+ concurrent users during competitions.

## 🎯 Overview

This project demonstrates the architecture and implementation of an online coding platform where users can:
- Browse and filter coding problems by difficulty and tags
- Submit solutions in multiple programming languages
- Participate in timed coding competitions
- View real-time leaderboards with rankings

## 📋 System Requirements

### Functional Requirements
- **Problem Catalog**: Paginated display of coding problems with metadata
- **Code Execution**: Sandboxed execution with 5-second timeout
- **Competition Support**: 90-minute contests with up to 10 problems
- **Live Leaderboard**: Real-time rankings during competitions

### Non-Functional Requirements
- **Scale**: Support 100,000 concurrent users
- **Performance**: Submission results within 5 seconds
- **Security**: Isolated code execution environment
- **Availability**: Prioritize availability over consistency
- **Throughput**: Handle 10,000 concurrent submissions

## 🏗️ Architecture

### High-Level Components

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │
       v
┌─────────────────────────────────────────┐
│         API Server (Spring Boot)        │
│  ┌─────────────────────────────────┐   │
│  │  REST Controllers               │   │
│  │  - Problems, Submissions        │   │
│  │  - Competitions, Leaderboard    │   │
│  └─────────────────────────────────┘   │
└──────┬──────────────────────────────────┘
       │
       ├──────────────┬─────────────────┬────────────────┐
       v              v                 v                v
┌─────────────┐ ┌──────────┐  ┌────────────────┐ ┌──────────┐
│  H2/JPA DB  │ │  Redis   │  │ Docker Engine  │ │   SQS    │
│  (Problems, │ │(Leaderbd)│  │ (Code Execute) │ │ (Queue)  │
│ Submissions)│ └──────────┘  └────────────────┘ └──────────┘
└─────────────┘
```

### Core Components

1. **API Server**: Spring Boot REST API handling all HTTP requests
2. **Database (H2/JPA)**: Stores problems, test cases, submissions, competitions
3. **Redis Cache**: Real-time leaderboard using sorted sets (ZSET)
4. **Docker Containers**: Isolated code execution environments
5. **Message Queue (SQS)**: Buffers submissions during peak load
6. **Thread Pool**: Async processing of code submissions

## 🔑 Key Design Decisions

### 1. Code Execution Strategy

**Chosen: Docker Containers**
- Lightweight and fast startup compared to VMs
- Strong isolation with security constraints
- Language-specific container images
- Read-only filesystem with temp output directory
- CPU/memory limits enforced
- 5-second execution timeout
- Network access disabled

**Security Measures:**
- Restricted system calls via seccomp
- No network access (VPC security groups)
- Resource limits (256MB memory, 50% CPU)
- Automatic container cleanup after execution

### 2. Leaderboard Implementation

**Redis Sorted Set (ZSET)**
```
Key: competition:leaderboard:{competitionId}
Score: -(problemsSolved * 1000000 - timeMs/1000)
Member: userId
```

**Benefits:**
- O(log N) insertion and retrieval
- Real-time updates without full table scans
- Built-in ranking support
- Efficient pagination

**Update Flow:**
1. User submits solution
2. Code executes successfully
3. Update submission count in database
4. Update Redis ZSET with new score
5. Client polls every 5 seconds for top N users

### 3. Async Processing with Thread Pool

**Why:**
- Prevents API server from blocking on long-running code execution
- Enables horizontal scaling of worker threads
- Supports retry logic on failures
- Better resource utilization

**Implementation:**
- Core pool: 10 threads
- Max pool: 50 threads
- Queue capacity: 500 submissions
- Custom thread naming for debugging

### 4. Capacity Planning

**Peak Load Scenario:**
- 10,000 concurrent submissions
- 100 test cases per submission
- 100ms per test case
- **Required**: ~1,667 CPU cores for 1-minute processing

**Horizontal Scaling:**
- Auto-scaling groups based on CPU/memory metrics
- Container instances scale up during competitions
- SQS queue prevents submission loss during spikes

## 📊 Data Model

### Problem Entity
```java
{
  id: UUID,
  title: String,
  question: Text,
  level: EASY|MEDIUM|HARD,
  tags: String[],
  codeStubs: Map<Language, String>,
  testCases: TestCase[]
}
```

### Submission Entity
```java
{
  id: UUID,
  userId: String,
  problemId: String,
  competitionId: String (optional),
  code: Text,
  language: String,
  status: QUEUED|PROCESSING|COMPLETED|FAILED,
  result: ACCEPTED|WRONG_ANSWER|TIME_LIMIT_EXCEEDED|...,
  testCasesPassed: Integer,
  totalTestCases: Integer,
  executionTimeMs: Long,
  submittedAt: DateTime,
  completedAt: DateTime
}
```

### Competition Entity
```java
{
  id: UUID,
  title: String,
  description: Text,
  startTime: DateTime,
  endTime: DateTime,
  durationMinutes: Integer,
  problemIds: String[],
  status: UPCOMING|ACTIVE|COMPLETED|CANCELLED
}
```

## 🔌 API Endpoints

### Problems
```
GET    /api/problems?page=1&limit=100          # List problems
GET    /api/problems/{id}?language=java        # Get problem details
GET    /api/problems/filter/tags?tags=array    # Filter by tags
GET    /api/problems/filter/difficulty?level=MEDIUM  # Filter by difficulty
POST   /api/problems                            # Create problem (admin)
PUT    /api/problems/{id}                       # Update problem
DELETE /api/problems/{id}                       # Delete problem
```

### Submissions
```
POST   /api/problems/{id}/submit                # Submit code
       Body: { userId, competitionId?, code, language }
       
GET    /api/submissions/{id}                    # Check submission status
GET    /api/users/{userId}/problems/{problemId}/submissions  # User's submissions
```

### Competitions
```
GET    /api/competitions                        # List all competitions
GET    /api/competitions/{id}                   # Get competition details
GET    /api/competitions/active                 # Active competitions
GET    /api/competitions/upcoming               # Upcoming competitions
POST   /api/competitions                        # Create competition
POST   /api/competitions/{id}/start             # Start competition
POST   /api/competitions/{id}/end               # End competition
DELETE /api/competitions/{id}                   # Cancel competition
```

### Leaderboard
```
GET    /api/leaderboard/{competitionId}?page=1&limit=100  # Get rankings
```

## 🚀 Getting Started

### Prerequisites
- Java 17+
- Maven 3.8+
- Docker Desktop (for code execution)
- Redis (for leaderboard)

### Running Locally

1. **Start Redis**
```bash
docker run -d -p 6379:6379 redis:alpine
```

2. **Build and Run**
```bash
cd leetcode
mvn clean install
mvn spring-boot:run
```

3. **Access H2 Console**
```
URL: http://localhost:8080/h2-console
JDBC URL: jdbc:h2:mem:leetcode
Username: sa
Password: (leave blank)
```

4. **Test API**
```bash
# Create a problem
curl -X POST http://localhost:8080/api/problems \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Two Sum",
    "question": "Find two numbers that add up to target",
    "level": "EASY",
    "tags": ["array", "hash-table"],
    "codeStubs": {
      "java": "public int[] twoSum(int[] nums, int target) { }"
    }
  }'

# Submit a solution
curl -X POST http://localhost:8080/api/problems/{problemId}/submit \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user123",
    "code": "public int[] twoSum(int[] nums, int target) { return new int[]{0,1}; }",
    "language": "java"
  }'

# Check submission status
curl http://localhost:8080/api/submissions/{submissionId}
```

## 📈 Scaling Strategies

### Horizontal Scaling
1. **API Server**: Stateless design enables easy replication
2. **Code Execution Workers**: Auto-scaling based on queue depth
3. **Database**: Read replicas for problem catalog queries
4. **Redis**: Redis Cluster for leaderboard sharding

### Performance Optimizations
1. **Caching**: Problem metadata cached in Redis (10-minute TTL)
2. **Pagination**: Limit result sets to prevent memory issues
3. **Async Processing**: Non-blocking submission handling
4. **Connection Pooling**: Efficient database connection reuse
5. **Batch Operations**: Bulk test case execution

### Rate Limiting
- Per-user submission limits (e.g., 10 submissions per minute)
- Competition-wide submission throttling
- API rate limiting with token bucket algorithm

## 🔒 Security Considerations

### Code Execution Sandbox
- **Filesystem**: Read-only root, writable temp directory only
- **Network**: Completely disabled
- **Resources**: Strict CPU and memory limits
- **Timeout**: Hard 5-second execution limit
- **System Calls**: Restricted via seccomp profiles

### Input Validation
- Code size limits (e.g., max 10KB)
- Language whitelist
- SQL injection prevention in queries
- XSS protection in responses

## 🧪 Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests
```bash
mvn verify
```

### Load Testing
Use tools like Apache JMeter or Gatling to simulate:
- 10,000 concurrent users
- 100 submissions per second
- Redis leaderboard updates under load

## 📦 Technology Stack

- **Framework**: Spring Boot 3.2.0
- **Language**: Java 17
- **Database**: H2 (in-memory), JPA/Hibernate
- **Cache**: Redis with Spring Data Redis
- **Containerization**: Docker Java API
- **Build Tool**: Maven
- **Testing**: JUnit 5, Spring Boot Test

## 🎓 Learning Objectives

This implementation demonstrates:
1. **Microservices Patterns**: Async processing, caching, queueing
2. **System Design**: Horizontal scaling, isolation, security
3. **Performance**: Sub-5-second response times at scale
4. **Real-time Systems**: Live leaderboard updates
5. **Resource Management**: Container orchestration, thread pools

## 📚 References

- [System Design Interview Guide](https://www.hellointerview.com/learn/system-design/problem-breakdowns/leetcode)
- [Docker Security Best Practices](https://docs.docker.com/engine/security/)
- [Redis Sorted Sets](https://redis.io/docs/data-types/sorted-sets/)
- [Spring Boot Async Processing](https://spring.io/guides/gs/async-method/)

## 📝 License

This is a system design educational project. Not for production use.
