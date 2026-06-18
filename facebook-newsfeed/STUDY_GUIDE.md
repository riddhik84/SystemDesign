# Facebook News Feed — Interview Study Guide

## 30-Second Pitch
"Facebook News Feed serves 10B feed requests/day to 500M DAU. Core challenge: fanout at scale—when a user with 1M followers posts, we can't write to 1M feeds synchronously. Solution: hybrid fanout—fanout-on-write for normal users (fast reads), fanout-on-read for celebrities (avoid fanout explosion). Feed stored in Redis sorted sets, ranked by recency + engagement + relationship strength."

## Key Numbers
- **500M DAU**, 10B feed reads/day = **115K QPS**
- **100M posts/day** = 1,150 writes/sec
- **Average 300 followers** → 345K fanout writes/sec
- **Feed latency target**: < 500 ms
- **Fanout latency**: < 5 seconds

## Critical Decision: Fanout Strategy

### Fanout-on-Write (Push)
- Write post → immediately push to all followers' feeds
- ✅ Fast reads (pre-computed)
- ❌ Slow writes (celebrity with 10M followers = 10M writes)
- ❌ Wasted work (90% of users never read their feed)

### Fanout-on-Read (Pull)
- Read feed → query posts from all followed users in real-time
- ✅ Fast writes (no fanout)
- ❌ Slow reads (must query 300 users × recent posts)

### Hybrid (Chosen)
- Normal users (< 1M followers): Fanout-on-write
- Celebrities (> 1M followers): Fanout-on-read
- ✅ Best of both: 99.9% get fast reads, celebrities avoid fanout explosion

## Common Questions

**Q: "How do you handle a celebrity with 100M followers?"**
A: Mark as celebrity (`is_celebrity = true`), skip fanout. At read time, fetch their posts separately and merge with pre-computed feed. Only affects 0.1% of users.

**Q: "What if user follows 5,000 people?"**
A: Limit active followings to 300 most recent. Alternative: Fetch top N most-engaged friends' posts only.

**Q: "How do you rank posts?"**
A: Score = recency × 50% + engagement × 30% + relationship strength × 15% + content preference × 5%. Store score in Redis sorted set.

**Q: "How do you scale Redis?"**
A: Shard by user ID (100 shards, each handles 5M users). Each feed is independent—no cross-shard queries.

## Trade-offs
| Factor | Fanout-on-Write | Fanout-on-Read | Hybrid |
|--------|-----------------|----------------|--------|
| Read latency | < 100 ms | > 1 sec | < 200 ms |
| Write latency | Seconds (fanout) | < 10 ms | < 50 ms |
| Storage | High (all feeds) | Low (posts only) | Medium |
| Complexity | Low | Medium | High |

## Red Flags
❌ "Store entire post in feed" → Too much data, use post IDs  
❌ "Fanout to all followers synchronously" → Blocks for seconds  
❌ "SQL JOIN across 300 friends" → Too slow  
✅ "Redis sorted set with post IDs, async fanout via Kafka"
