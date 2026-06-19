# LeetCode Platform - Quick Start Guide

Get the platform running locally in under 5 minutes.

## Prerequisites

- Java 17 or higher
- Maven 3.8+
- Docker Desktop (for code execution)
- Redis (for leaderboard)

## Step 1: Start Redis

```bash
docker run -d --name redis-leetcode -p 6379:6379 redis:alpine
```

Verify Redis is running:
```bash
docker ps | grep redis-leetcode
```

## Step 2: Build & Run

```bash
mvn clean install
mvn spring-boot:run
```

The application starts on `http://localhost:8080`

## Step 3: Verify It Works

### Check H2 Database Console
- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:leetcode`
- Username: `sa`
- Password: (leave blank)

### Test the API

**1. Create a Problem**
```bash
curl -X POST http://localhost:8080/api/problems \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Two Sum",
    "question": "Given an array of integers nums and an integer target, return indices of the two numbers such that they add up to target.",
    "level": "EASY",
    "tags": ["array", "hash-table"],
    "codeStubs": {
      "java": "public int[] twoSum(int[] nums, int target) {\n    // Your code here\n    return new int[]{0, 1};\n}"
    }
  }'
```

Save the returned problem ID from the response.

**2. Create Test Cases**

Open H2 Console and insert test cases:
```sql
-- Replace 'YOUR_PROBLEM_ID' with the ID from step 1
INSERT INTO test_cases (id, problem_id, type, input, expected_output)
VALUES 
  (RANDOM_UUID(), 'YOUR_PROBLEM_ID', 'SAMPLE', '[2,7,11,15], 9', '[0,1]'),
  (RANDOM_UUID(), 'YOUR_PROBLEM_ID', 'HIDDEN', '[3,2,4], 6', '[1,2]');
```

**3. Create a Competition**
```bash
curl -X POST http://localhost:8080/api/competitions \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Weekly Contest 1",
    "description": "Test competition",
    "startTime": "2024-01-20T10:00:00",
    "endTime": "2024-01-20T11:30:00",
    "durationMinutes": 90,
    "problemIds": ["YOUR_PROBLEM_ID"]
  }'
```

Save the competition ID.

**4. Start the Competition**
```bash
curl -X POST http://localhost:8080/api/competitions/YOUR_COMPETITION_ID/start
```

**5. Submit a Solution**
```bash
curl -X POST http://localhost:8080/api/problems/YOUR_PROBLEM_ID/submit \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user123",
    "competitionId": "YOUR_COMPETITION_ID",
    "code": "public int[] twoSum(int[] nums, int target) { return new int[]{0,1}; }",
    "language": "java"
  }'
```

You'll receive a response like:
```json
{
  "submissionId": "sub-abc-123",
  "status": "QUEUED",
  "message": "Submission queued for processing"
}
```

**6. Check Submission Status**
```bash
curl http://localhost:8080/api/submissions/sub-abc-123
```

Poll this endpoint every 1 second until status is `COMPLETED`.

**7. View Leaderboard**
```bash
curl http://localhost:8080/api/leaderboard/YOUR_COMPETITION_ID
```

Expected response:
```json
[
  {
    "userId": "user123",
    "username": "User123",
    "rank": 1,
    "problemsSolved": 1,
    "totalTimeMs": 15000,
    "score": 999985.0
  }
]
```

## Troubleshooting

### Redis Connection Error
```
Error: Cannot connect to Redis at localhost:6379
```
**Fix:** Ensure Redis container is running:
```bash
docker start redis-leetcode
```

### Docker Permission Error
```
Error: Got permission denied while trying to connect to Docker daemon
```
**Fix:** Ensure Docker Desktop is running and you have permissions:
```bash
# On macOS/Linux
sudo chmod 666 /var/run/docker.sock
```

### Code Execution Timeout
```
Error: Execution timed out after 5 seconds
```
**Fix:** This is expected for infinite loops or very slow code. The system correctly kills the container after 5 seconds.

### Port Already in Use
```
Error: Port 8080 is already in use
```
**Fix:** Change the port in `application.yml`:
```yaml
server:
  port: 8081  # Or any other available port
```

## What's Next?

1. **Read the Architecture**: Check [ARCHITECTURE.md](./ARCHITECTURE.md) for system diagrams
2. **Study the Code**: Follow [CODE_WALKTHROUGH.md](./CODE_WALKTHROUGH.md) for detailed explanations
3. **Interview Prep**: Use [STUDY_GUIDE.md](./STUDY_GUIDE.md) for interview practice
4. **Full Documentation**: See [README.md](./README.md) for complete details

## API Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/problems` | GET | List all problems (paginated) |
| `/api/problems/{id}` | GET | Get problem details |
| `/api/problems/{id}/submit` | POST | Submit a solution |
| `/api/submissions/{id}` | GET | Check submission status |
| `/api/competitions` | GET | List competitions |
| `/api/competitions/{id}/start` | POST | Start a competition |
| `/api/leaderboard/{competitionId}` | GET | Get competition leaderboard |

## Sample Test Scenarios

### Scenario 1: Correct Solution
```json
{
  "code": "public int[] twoSum(int[] nums, int target) { Map<Integer, Integer> map = new HashMap<>(); for(int i = 0; i < nums.length; i++) { int complement = target - nums[i]; if(map.containsKey(complement)) { return new int[]{map.get(complement), i}; } map.put(nums[i], i); } return new int[]{0, 1}; }",
  "language": "java"
}
```
**Expected:** `result: "ACCEPTED"`

### Scenario 2: Wrong Answer
```json
{
  "code": "public int[] twoSum(int[] nums, int target) { return new int[]{1,0}; }",
  "language": "java"
}
```
**Expected:** `result: "WRONG_ANSWER"`, output shows expected vs actual

### Scenario 3: Runtime Error
```json
{
  "code": "public int[] twoSum(int[] nums, int target) { return nums[100]; }",
  "language": "java"
}
```
**Expected:** `result: "RUNTIME_ERROR"`, error message shows ArrayIndexOutOfBoundsException

### Scenario 4: Time Limit Exceeded
```json
{
  "code": "public int[] twoSum(int[] nums, int target) { while(true) {} return new int[]{0,1}; }",
  "language": "java"
}
```
**Expected:** `result: "TIME_LIMIT_EXCEEDED"`

## Development Tips

### Watch Logs in Real-Time
```bash
mvn spring-boot:run | grep -E "(Submission|Docker|Redis)"
```

### Monitor Redis
```bash
docker exec -it redis-leetcode redis-cli

# View leaderboard
ZRANGE competition:leaderboard:YOUR_COMPETITION_ID 0 -1 WITHSCORES

# View user data
HGETALL competition:user:YOUR_COMPETITION_ID:user123
```

### Check Database State
```bash
# In H2 Console
SELECT * FROM submissions ORDER BY submitted_at DESC;
SELECT * FROM problems;
SELECT * FROM competitions;
```

### Clean Up Docker Containers
```bash
# Remove stopped containers
docker container prune -f

# View running containers
docker ps

# Stop all containers
docker stop $(docker ps -q)
```

## Production Considerations

For production deployment, replace:
- **H2** with PostgreSQL
- **Single Redis** with Redis Cluster
- **Local Docker** with Kubernetes + Docker hosts
- **In-memory queue** with AWS SQS or RabbitMQ

See [README.md](./README.md) section "Scaling Strategies" for details.

---

**Happy Coding! 🚀**
