# LeetCode Platform - Code Walkthrough

This guide walks you through the codebase step-by-step, explaining how each component works and how they fit together.

## 🗺️ Project Structure

```
leetcode/
├── src/main/java/com/systemdesign/leetcode/
│   ├── LeetCodeApplication.java          # Spring Boot entry point
│   ├── model/                             # Domain entities
│   │   ├── Problem.java                   # Problem entity with test cases
│   │   ├── TestCase.java                  # Test case entity
│   │   ├── Submission.java                # Code submission entity
│   │   ├── Competition.java               # Competition entity
│   │   └── LeaderboardEntry.java          # Leaderboard DTO
│   ├── repository/                        # Data access layer
│   │   ├── ProblemRepository.java         # Problem CRUD + queries
│   │   ├── SubmissionRepository.java      # Submission CRUD + queries
│   │   ├── CompetitionRepository.java     # Competition CRUD
│   │   └── TestCaseRepository.java        # Test case queries
│   ├── service/                           # Business logic
│   │   ├── ProblemService.java            # Problem management
│   │   ├── CodeExecutionService.java      # Docker-based code execution
│   │   ├── SubmissionService.java         # Async submission processing
│   │   ├── LeaderboardService.java        # Redis-based leaderboard
│   │   └── CompetitionService.java        # Competition lifecycle
│   ├── controller/                        # REST API endpoints
│   │   ├── ProblemController.java         # Problem CRUD endpoints
│   │   ├── SubmissionController.java      # Submit & check status
│   │   ├── LeaderboardController.java     # Leaderboard queries
│   │   └── CompetitionController.java     # Competition management
│   └── config/                            # Application configuration
│       ├── RedisConfig.java               # Redis connection setup
│       └── AsyncConfig.java               # Thread pool for async tasks
└── src/main/resources/
    └── application.yml                    # Spring Boot configuration
```

## 🏗️ Architecture Flow

### 1. User Submits Code

```
User → ProblemController.submitCode()
    → SubmissionService.submitCode()
    → Create Submission (status: QUEUED)
    → Save to Database
    → SubmissionService.processSubmissionAsync() [background thread]
    → Return 202 Accepted with submissionId
```

### 2. Async Code Execution

```
Background Thread:
    → CodeExecutionService.executeCode()
    → Create temp directory
    → Write code to file
    → For each test case:
        → Create Docker container with security constraints
        → Execute code with timeout
        → Compare output with expected
        → Clean up container
    → Update submission status (COMPLETED)
    → If ACCEPTED in competition → LeaderboardService.updateLeaderboard()
```

### 3. Leaderboard Update

```
LeaderboardService.updateLeaderboard()
    → Count user's solved problems in competition
    → Calculate score: problemsSolved × 1M - timeMs/1000
    → Redis ZADD competition:leaderboard:{id} -score userId
    → Store user metadata in Redis Hash
    → Set TTL (7 days)
```

---

## 📦 Core Components Deep Dive

## 1️⃣ Data Models (`model/`)

### Problem.java

**Purpose:** Represents a coding problem with test cases and language-specific code stubs.

**Key Fields:**
```java
@Entity
public class Problem {
    private String id;                              // UUID primary key
    private String title;                           // "Two Sum"
    private String question;                        // Problem description
    private DifficultyLevel level;                  // EASY, MEDIUM, HARD
    private List<String> tags;                      // ["array", "hash-table"]
    private Map<String, String> codeStubs;          // {"java": "...", "python": "..."}
    private List<TestCase> testCases;               // One-to-many relationship
}
```

**Design Notes:**
- `@ElementCollection` for tags: Simple list stored in separate table
- `@ElementCollection` for codeStubs: Map stored as key-value in separate table
- `@OneToMany` for testCases: Each problem has multiple test cases
- `@JsonIgnore` on testCases: Don't expose test cases in API responses

**Why these choices?**
- Tags and stubs rarely change → separate tables OK
- Test cases are critical data → need relational integrity with foreign key

### Submission.java

**Purpose:** Tracks code submission lifecycle from queue to execution to result.

**Key Fields:**
```java
@Entity
public class Submission {
    private String id;
    private String userId;
    private String problemId;
    private String competitionId;                   // Optional
    private String code;                            // User's solution
    private String language;                        // "java", "python", etc.
    private SubmissionStatus status;                // QUEUED → PROCESSING → COMPLETED
    private SubmissionResult result;                // ACCEPTED, WRONG_ANSWER, etc.
    private Integer testCasesPassed;
    private Integer totalTestCases;
    private Long executionTimeMs;
    private LocalDateTime submittedAt;
    private LocalDateTime completedAt;
}
```

**State Machine:**
```
QUEUED → PROCESSING → COMPLETED (with result: ACCEPTED/WRONG_ANSWER/TLE/ERROR)
                   ↓
                 FAILED (system error)
```

**Indexes:**
- `(userId, problemId)`: Fast lookup of user's submission history
- `competitionId`: Fast leaderboard calculations

### Competition.java

**Purpose:** Represents a timed coding contest with multiple problems.

**Key Fields:**
```java
@Entity
public class Competition {
    private String id;
    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;                // 90 for typical contest
    private List<String> problemIds;                // 10 problems per competition
    private CompetitionStatus status;               // UPCOMING → ACTIVE → COMPLETED
}
```

**Lifecycle:**
```
UPCOMING (created) → ACTIVE (started) → COMPLETED (ended)
                                     ↓
                                  CANCELLED
```

---

## 2️⃣ Services (`service/`)

### CodeExecutionService.java ⭐ **Most Complex Component**

**Purpose:** Executes user code in isolated Docker containers with security constraints.

**Architecture:**
```
executeCode(code, language, testCases)
    ↓
Create temp directory + code file
    ↓
For each test case:
    ↓
createContainer(language-specific image)
    ↓
Apply security constraints:
    - Memory limit: 256MB
    - CPU quota: 50% of one core
    - Network: disabled
    - Filesystem: read-only except /tmp
    ↓
Execute code with 5-second timeout
    ↓
Compare output with expected
    ↓
Stop & remove container
    ↓
Return result (ACCEPTED/WRONG_ANSWER/TLE/ERROR)
```

**Key Methods:**

#### `executeCode()`
```java
public SubmissionResult executeCode(
    String code,
    String language,
    List<TestCase> testCases,
    Submission submission
) {
    // 1. Create temp directory and code file
    Path tempDir = Files.createTempDirectory("leetcode-exec");
    File codeFile = createCodeFile(tempDir, code, language);
    
    // 2. Get language-specific Docker image
    String imageName = getDockerImageForLanguage(language);
    // java → "openjdk:17-slim"
    // python → "python:3.11-slim"
    
    // 3. Execute each test case
    for (TestCase testCase : testCases) {
        ExecutionResult result = executeTestCase(...);
        
        // 4. Check for timeout
        if (result.executionTimeMs > 5000) {
            return TIME_LIMIT_EXCEEDED;
        }
        
        // 5. Check for runtime errors
        if (result.exitCode != 0) {
            return RUNTIME_ERROR;
        }
        
        // 6. Compare output
        if (!result.stdout.equals(testCase.expectedOutput)) {
            return WRONG_ANSWER;
        }
    }
    
    // 7. All tests passed
    return ACCEPTED;
}
```

#### `executeTestCase()` - Docker Container Orchestration

```java
private ExecutionResult executeTestCase(...) {
    // 1. Configure security constraints
    HostConfig hostConfig = new HostConfig()
        .withMemory(256 * 1024 * 1024)          // 256MB limit
        .withCpuQuota(50000)                    // 50% of one CPU
        .withNetworkMode("none")                // No network access
        .withReadonlyRootfs(true)               // Read-only filesystem
        .withBinds(new Bind(tempDir, "/workspace"));
    
    // 2. Create container
    CreateContainerResponse container = dockerClient
        .createContainerCmd(imageName)
        .withHostConfig(hostConfig)
        .withWorkingDir("/workspace")
        .exec();
    
    // 3. Start container
    dockerClient.startContainerCmd(containerId).exec();
    
    // 4. Execute code with command based on language
    String cmd = buildExecuteCommand(language, fileName);
    // java: "javac Solution.java && java Solution"
    // python: "python Solution.py"
    
    ExecCreateCmdResponse execCmd = dockerClient
        .execCreateCmd(containerId)
        .withCmd("/bin/sh", "-c", cmd)
        .exec();
    
    // 5. Capture stdout/stderr with 5-second timeout
    long startTime = System.currentTimeMillis();
    dockerClient.execStartCmd(execCmd.getId())
        .exec(new ExecCallback(stdout, stderr))
        .awaitCompletion(5000, TimeUnit.MILLISECONDS);
    
    long executionTime = System.currentTimeMillis() - startTime;
    
    // 6. Get exit code
    Integer exitCode = dockerClient.inspectExecCmd(execCmd.getId())
        .exec()
        .getExitCodeLong()
        .intValue();
    
    // 7. Cleanup
    dockerClient.stopContainerCmd(containerId).exec();
    dockerClient.removeContainerCmd(containerId).exec();
    
    return new ExecutionResult(stdout, stderr, exitCode, executionTime);
}
```

**Security Deep Dive:**

1. **Memory Limit (256MB):**
   - Prevents memory bombs (infinite arrays)
   - Container killed if exceeded

2. **CPU Quota (50%):**
   - Prevents CPU spinning (infinite loops still hit timeout)
   - Fair resource sharing across containers

3. **Network Disabled:**
   - Prevents external API calls
   - No data exfiltration

4. **Read-only Filesystem:**
   - Prevents file system exploits
   - Only /tmp is writable (via bind mount)

5. **5-Second Timeout:**
   - Hard limit enforced by `awaitCompletion()`
   - Container killed after timeout

### SubmissionService.java

**Purpose:** Orchestrates async submission processing.

**Flow:**
```java
@Transactional
public Submission submitCode(...) {
    // 1. Create submission record (status: QUEUED)
    Submission submission = Submission.builder()
        .status(QUEUED)
        .submittedAt(LocalDateTime.now())
        .build();
    
    // 2. Save to database
    submission = submissionRepository.save(submission);
    
    // 3. Trigger async processing (returns immediately)
    processSubmissionAsync(submission.getId());
    
    // 4. Return to client (202 Accepted)
    return submission;
}

@Async  // ← Runs in separate thread from pool
public CompletableFuture<Void> processSubmissionAsync(String submissionId) {
    try {
        // 1. Update status to PROCESSING
        submission.setStatus(PROCESSING);
        submissionRepository.save(submission);
        
        // 2. Get test cases
        List<TestCase> testCases = testCaseRepository.findByProblemId(...);
        
        // 3. Execute code (blocking call)
        SubmissionResult result = codeExecutionService.executeCode(...);
        
        // 4. Update submission with result
        submission.setResult(result);
        submission.setStatus(COMPLETED);
        submission.setCompletedAt(LocalDateTime.now());
        submissionRepository.save(submission);
        
        // 5. Update leaderboard if accepted in competition
        if (result == ACCEPTED && competitionId != null) {
            leaderboardService.updateLeaderboard(...);
        }
        
    } catch (Exception e) {
        // Mark as FAILED on system error
        submission.setStatus(FAILED);
        submissionRepository.save(submission);
    }
    
    return CompletableFuture.completedFuture(null);
}
```

**Why @Async?**
- Prevents blocking API thread for 5+ seconds
- Enables horizontal scaling of workers
- Thread pool configured in `AsyncConfig.java`:
  - Core pool: 10 threads
  - Max pool: 50 threads
  - Queue: 500 submissions

### LeaderboardService.java ⭐ **Redis Sorted Set Magic**

**Purpose:** Maintain real-time leaderboard using Redis ZSET.

**Data Structure:**
```
Redis ZSET:
  Key: "competition:leaderboard:{competitionId}"
  Score: -(problemsSolved × 1,000,000 - timeMs/1000)
  Member: userId

Redis Hash:
  Key: "competition:user:{competitionId}:{userId}"
  Fields:
    - problemsSolved: "5"
    - totalTimeMs: "3600000"
    - score: "4996400"
```

**Why negative scores?**
```
Higher is better, but Redis ZRANGE returns ascending order:
  score = 4,996,400 → stored as -4,996,400
  
ZRANGE (ascending) = [-4,996,400, -2,500,000, -1,000,000]
                   = [Rank 1, Rank 2, Rank 3]
```

**Key Methods:**

#### `updateLeaderboard()` - Called after accepted submission

```java
public void updateLeaderboard(String competitionId, String userId, LocalDateTime completionTime) {
    // 1. Count total problems solved by user in this competition
    Integer problemsSolved = submissionRepository
        .countSolvedProblemsByUserInCompetition(userId, competitionId);
    
    // 2. Calculate total time spent
    Long totalTimeMs = calculateTotalTime(userId, competitionId, completionTime);
    
    // 3. Calculate score (prioritize problems over time)
    double score = (problemsSolved × 1_000_000.0) - (totalTimeMs / 1000.0);
    // Example: 5 problems in 60 minutes = 5,000,000 - 3,600 = 4,996,400
    
    // 4. Update Redis ZSET (negative score for ascending order)
    ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
    zSetOps.add(leaderboardKey, userId, -score);
    
    // 5. Store user metadata in Redis Hash
    redisTemplate.opsForHash().put(userDataKey, "problemsSolved", problemsSolved);
    redisTemplate.opsForHash().put(userDataKey, "totalTimeMs", totalTimeMs);
    redisTemplate.opsForHash().put(userDataKey, "score", score);
    
    // 6. Set TTL (7 days)
    redisTemplate.expire(leaderboardKey, Duration.ofDays(7));
}
```

**Time Complexity:**
- `ZADD`: O(log N) where N = number of participants
- `ZRANGE`: O(log N + M) where M = limit (e.g., top 100)

#### `getLeaderboard()` - Retrieve top N users

```java
public List<LeaderboardEntry> getLeaderboard(String competitionId, int page, int limit) {
    // 1. Calculate range for pagination
    int start = (page - 1) × limit;     // page 1 → start 0
    int end = start + limit - 1;        // limit 100 → end 99
    
    // 2. Get top users with scores from Redis ZSET
    Set<TypedTuple<String>> topUsers = zSetOps.rangeWithScores(leaderboardKey, start, end);
    
    // 3. Build leaderboard entries
    List<LeaderboardEntry> leaderboard = new ArrayList<>();
    int rank = start + 1;
    
    for (TypedTuple<String> tuple : topUsers) {
        String userId = tuple.getValue();
        Double score = tuple.getScore();        // Negative value
        
        // 4. Get user metadata from Redis Hash
        String problemsSolvedStr = redisTemplate.opsForHash()
            .get(userDataKey, "problemsSolved");
        
        // 5. Build entry
        LeaderboardEntry entry = LeaderboardEntry.builder()
            .userId(userId)
            .rank(rank++)
            .problemsSolved(Integer.parseInt(problemsSolvedStr))
            .score(Math.abs(score))             // Convert back to positive
            .build();
        
        leaderboard.add(entry);
    }
    
    return leaderboard;
}
```

**Example Redis Data:**

After 3 users complete submissions:
```redis
# ZSET (leaderboard)
ZRANGE competition:leaderboard:123 0 -1 WITHSCORES
1) "user456"
2) "-4996400"      # 5 problems, 60 minutes
3) "user789"
4) "-2997200"      # 3 problems, 45 minutes
5) "user123"
6) "-1998000"      # 2 problems, 30 minutes

# Hash (user metadata)
HGETALL competition:user:123:user456
1) "problemsSolved"
2) "5"
3) "totalTimeMs"
4) "3600000"
5) "score"
6) "4996400"
```

---

## 3️⃣ Controllers (`controller/`)

### SubmissionController.java

**Purpose:** REST API for submitting code and checking status.

**Key Endpoints:**

#### POST `/api/problems/{problemId}/submit`

```java
@PostMapping("/problems/{problemId}/submit")
public ResponseEntity<SubmissionResponse> submitCode(
    @PathVariable String problemId,
    @RequestBody SubmissionRequest request
) {
    // 1. Call service to create submission (async)
    Submission submission = submissionService.submitCode(
        request.getUserId(),
        problemId,
        request.getCompetitionId(),
        request.getCode(),
        request.getLanguage()
    );
    
    // 2. Return 202 Accepted immediately
    SubmissionResponse response = new SubmissionResponse();
    response.setSubmissionId(submission.getId());
    response.setStatus("QUEUED");
    response.setMessage("Submission queued for processing");
    
    return ResponseEntity.accepted().body(response);
}
```

**HTTP Flow:**
```http
POST /api/problems/abc123/submit
Content-Type: application/json

{
  "userId": "user456",
  "competitionId": "comp789",
  "code": "public int[] twoSum(int[] nums, int target) { ... }",
  "language": "java"
}

Response: 202 Accepted
{
  "submissionId": "sub999",
  "status": "QUEUED",
  "message": "Submission queued for processing"
}
```

**Client Polling:**
```javascript
// Client-side pseudocode
const submissionId = response.submissionId;

const intervalId = setInterval(async () => {
    const status = await fetch(`/api/submissions/${submissionId}`);
    
    if (status.status === 'COMPLETED') {
        clearInterval(intervalId);
        if (status.result === 'ACCEPTED') {
            showSuccess();
        } else {
            showError(status.result);
        }
    }
}, 1000);  // Poll every 1 second
```

#### GET `/api/submissions/{id}`

```java
@GetMapping("/submissions/{id}")
public ResponseEntity<Submission> getSubmissionStatus(@PathVariable String id) {
    Submission submission = submissionService.getSubmissionStatus(id);
    return ResponseEntity.ok(submission);
}
```

**Response Evolution:**
```json
# Immediately after submit (QUEUED)
{
  "id": "sub999",
  "status": "QUEUED",
  "result": null,
  "testCasesPassed": null,
  "submittedAt": "2024-01-15T10:30:00"
}

# During execution (PROCESSING)
{
  "id": "sub999",
  "status": "PROCESSING",
  "result": null,
  "testCasesPassed": null,
  "submittedAt": "2024-01-15T10:30:00"
}

# After completion (COMPLETED - ACCEPTED)
{
  "id": "sub999",
  "status": "COMPLETED",
  "result": "ACCEPTED",
  "testCasesPassed": 100,
  "totalTestCases": 100,
  "executionTimeMs": 1523,
  "submittedAt": "2024-01-15T10:30:00",
  "completedAt": "2024-01-15T10:30:04"
}

# After completion (COMPLETED - WRONG_ANSWER)
{
  "id": "sub999",
  "status": "COMPLETED",
  "result": "WRONG_ANSWER",
  "testCasesPassed": 87,
  "totalTestCases": 100,
  "output": "Expected: [0,1]\nGot: [1,0]",
  "submittedAt": "2024-01-15T10:30:00",
  "completedAt": "2024-01-15T10:30:03"
}
```

### LeaderboardController.java

**Purpose:** Retrieve real-time leaderboard for competitions.

```java
@GetMapping("/{competitionId}")
public ResponseEntity<List<LeaderboardEntry>> getLeaderboard(
    @PathVariable String competitionId,
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "100") int limit
) {
    List<LeaderboardEntry> leaderboard = 
        leaderboardService.getLeaderboard(competitionId, page, limit);
    return ResponseEntity.ok(leaderboard);
}
```

**Example Request/Response:**
```http
GET /api/leaderboard/comp789?page=1&limit=10

Response:
[
  {
    "userId": "user456",
    "username": "User456",
    "rank": 1,
    "problemsSolved": 8,
    "totalTimeMs": 4500000,
    "score": 7995500.0
  },
  {
    "userId": "user123",
    "rank": 2,
    "problemsSolved": 7,
    "totalTimeMs": 3900000,
    "score": 6996100.0
  },
  ...
]
```

---

## 4️⃣ Configuration (`config/`)

### AsyncConfig.java

**Purpose:** Configure thread pool for async code execution.

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);           // Always-alive threads
        executor.setMaxPoolSize(50);            // Scale up to 50 under load
        executor.setQueueCapacity(500);         // Queue up to 500 submissions
        executor.setThreadNamePrefix("submission-worker-");
        executor.initialize();
        return executor;
    }
}
```

**Thread Pool Behavior:**
1. First 10 submissions → use core threads
2. Next 500 submissions → queue up
3. Beyond 510 submissions → create new threads (up to 50 total)
4. Beyond 50 threads + 500 queue → reject with exception

**Production Tuning:**
- Core pool = 2 × CPU cores
- Max pool = 4 × CPU cores
- Queue capacity = expected burst size

### RedisConfig.java

**Purpose:** Configure Redis for leaderboard caching.

```java
@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        
        // Use String serialization for human-readable keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        
        return template;
    }
}
```

---

## 🔄 End-to-End Flow Example

### Scenario: User submits "Two Sum" solution during competition

**Step 1: User submits code**
```http
POST /api/problems/prob123/submit
{
  "userId": "user456",
  "competitionId": "comp789",
  "code": "public int[] twoSum(...) { Map<Integer, Integer> map = new HashMap<>(); ... }",
  "language": "java"
}
```

**Step 2: Controller → Service (synchronous)**
```java
// SubmissionController
Submission submission = submissionService.submitCode(...);

// SubmissionService
Submission sub = Submission.builder()
    .status(QUEUED)
    .submittedAt(now())
    .build();
submissionRepository.save(sub);         // Database insert
processSubmissionAsync(sub.getId());     // Trigger background thread
return sub;                              // Return immediately
```

**Response to client:**
```http
202 Accepted
{
  "submissionId": "sub999",
  "status": "QUEUED"
}
```

**Step 3: Async worker processes submission (background)**
```java
// Background thread from pool
submission.setStatus(PROCESSING);
submissionRepository.save(submission);

// Get test cases from database
List<TestCase> testCases = testCaseRepository.findByProblemId("prob123");
// Returns: [{input: "[2,7,11,15], 9", output: "[0,1]"}, ...]

// Execute code
SubmissionResult result = codeExecutionService.executeCode(
    code, "java", testCases, submission
);
```

**Step 4: Docker execution**
```java
// For each test case:
1. Create temp directory: /tmp/leetcode-exec-abc123/
2. Write file: Solution.java
3. Create Docker container: openjdk:17-slim
4. Apply security: 256MB RAM, 50% CPU, no network, read-only FS
5. Execute: "javac Solution.java && java Solution"
6. Capture output: "[0,1]"
7. Compare: "[0,1]" == "[0,1]" ✓
8. Stop & remove container
9. Repeat for all 100 test cases
```

**Step 5: Update database with result**
```java
submission.setResult(ACCEPTED);
submission.setStatus(COMPLETED);
submission.setTestCasesPassed(100);
submission.setTotalTestCases(100);
submission.setExecutionTimeMs(1523);
submission.setCompletedAt(now());
submissionRepository.save(submission);
```

**Step 6: Update leaderboard (if competition)**
```java
leaderboardService.updateLeaderboard("comp789", "user456", now());

// Count solved problems
Integer solved = submissionRepository.countSolvedProblemsByUserInCompetition("user456", "comp789");
// Returns: 3 problems

// Calculate score
Long timeMs = 2700000;  // 45 minutes
double score = (3 × 1,000,000) - (2700000 / 1000) = 2,997,300

// Update Redis
ZADD competition:leaderboard:comp789 -2997300 user456
HSET competition:user:comp789:user456 problemsSolved 3
HSET competition:user:comp789:user456 totalTimeMs 2700000
EXPIRE competition:leaderboard:comp789 604800  // 7 days
```

**Step 7: Client polls for result**
```http
GET /api/submissions/sub999

Response:
{
  "id": "sub999",
  "status": "COMPLETED",
  "result": "ACCEPTED",
  "testCasesPassed": 100,
  "totalTestCases": 100,
  "executionTimeMs": 1523
}
```

**Step 8: Client fetches updated leaderboard**
```http
GET /api/leaderboard/comp789?page=1&limit=10

Response:
[
  {
    "userId": "user789",
    "rank": 1,
    "problemsSolved": 5,
    "score": 4996400
  },
  {
    "userId": "user456",    ← Our user
    "rank": 2,
    "problemsSolved": 3,
    "score": 2997300
  },
  ...
]
```

---

## 🎯 Key Design Patterns Used

### 1. Repository Pattern
```
Controller → Service → Repository → Database
```
- Separates data access from business logic
- Easy to test with mock repositories
- Can swap database implementations

### 2. Async Processing Pattern
```
Client → Controller → Service → @Async method → Background thread
```
- Non-blocking API responses
- Horizontal scaling of workers
- Natural retry mechanism

### 3. Builder Pattern
```java
Submission submission = Submission.builder()
    .userId("user123")
    .code("...")
    .status(QUEUED)
    .build();
```
- Immutable object construction
- Readable code
- Optional fields handled gracefully

### 4. Strategy Pattern (implicit)
```java
String image = getDockerImageForLanguage(language);
String command = buildExecuteCommand(language, fileName);
```
- Different execution strategies per language
- Easy to add new languages

### 5. Template Method Pattern
```java
// CodeExecutionService.executeCode() defines template:
1. Setup environment
2. Execute test cases (abstract)
3. Compare results
4. Cleanup
```

---

## 🔍 Common Questions

### Q: Why not WebSockets instead of polling?

**A:** Polling is simpler and sufficient for 5-second execution times.
- WebSocket: Requires connection management, reconnection logic, more complex
- Polling: Stateless, works with load balancers, client controls rate
- Trade-off: Extra HTTP requests vs. added complexity
- At 1-second poll interval, it's 5 requests per submission (acceptable)

### Q: How does this scale to 100K concurrent users?

**A:** Horizontal scaling at multiple levels:
1. **API servers**: Stateless, add more behind load balancer
2. **Worker threads**: Increase pool size (core: 100, max: 500)
3. **Docker host**: Multiple machines running Docker Engine
4. **Database**: Read replicas for problem queries, master for writes
5. **Redis**: Redis Cluster shards leaderboards by competitionId

### Q: What happens if Docker daemon crashes?

**A:** Graceful degradation:
1. Worker thread catches exception
2. Submission marked as FAILED
3. User can resubmit
4. Production: Health checks restart Docker daemon
5. Consider multiple Docker hosts with load balancing

### Q: How do you prevent code that writes infinite data?

**A:** Multiple limits:
1. Memory limit (256MB): Container killed if exceeded
2. Disk limit: Read-only filesystem except /tmp
3. /tmp is in-memory (tmpfs): Shares memory limit
4. Timeout: 5 seconds max, then killed

### Q: Can users see other users' code?

**A:** No, by design:
- Submissions table stores code, but no API endpoint exposes it
- Only return submission status, result, and test case pass count
- Production: Encrypt code at rest in database

---

## 🚀 Running the Code

### Start Redis
```bash
docker run -d -p 6379:6379 redis:alpine
```

### Run Application
```bash
cd leetcode
mvn clean install
mvn spring-boot:run
```

### Test Sequence

**1. Create a problem**
```bash
curl -X POST http://localhost:8080/api/problems \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Two Sum",
    "question": "Given an array of integers nums and an integer target, return indices of the two numbers such that they add up to target.",
    "level": "EASY",
    "tags": ["array", "hash-table"],
    "codeStubs": {
      "java": "public int[] twoSum(int[] nums, int target) {\n    // Your code here\n}",
      "python": "def two_sum(nums, target):\n    # Your code here\n    pass"
    }
  }'
```

**2. Create test cases**
```bash
# Manually insert via H2 console:
# http://localhost:8080/h2-console
INSERT INTO test_cases (id, problem_id, type, input, expected_output)
VALUES 
  ('tc1', 'prob123', 'SAMPLE', '[2,7,11,15], 9', '[0,1]'),
  ('tc2', 'prob123', 'HIDDEN', '[3,2,4], 6', '[1,2]');
```

**3. Submit solution**
```bash
curl -X POST http://localhost:8080/api/problems/prob123/submit \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user456",
    "code": "public int[] twoSum(int[] nums, int target) { return new int[]{0,1}; }",
    "language": "java"
  }'
```

**4. Check status**
```bash
curl http://localhost:8080/api/submissions/sub999
```

**5. View leaderboard**
```bash
curl http://localhost:8080/api/leaderboard/comp789
```

---

## 📖 Further Reading

- **Docker Security**: [docs.docker.com/engine/security](https://docs.docker.com/engine/security/)
- **Redis Sorted Sets**: [redis.io/docs/data-types/sorted-sets](https://redis.io/docs/data-types/sorted-sets/)
- **Spring @Async**: [spring.io/guides/gs/async-method](https://spring.io/guides/gs/async-method/)
- **Thread Pool Sizing**: [jenkov.com/tutorials/java-concurrency/thread-pools.html](http://tutorials.jenkov.com/java-concurrency/thread-pools.html)

---

**Happy coding! 🚀**
