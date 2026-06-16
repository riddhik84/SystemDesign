package com.systemdesign.bitly.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates globally-unique, URL-safe short codes using a counter-based approach with Redis
 * INCRBY batching.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>A single Redis key ({@code "url:counter"}) holds the global monotonic counter.</li>
 *   <li>Each service instance claims a batch of {@value #BATCH_SIZE} counter values atomically
 *       via {@code INCRBY url:counter 1000}.  The returned value {@code N} means this instance
 *       owns the range {@code [N - BATCH_SIZE + 1, N]} (inclusive).</li>
 *   <li>Within a batch, the instance assigns codes locally with zero Redis round-trips, using an
 *       {@link AtomicLong} for thread safety.</li>
 *   <li>When the local batch is exhausted (currentCounter &ge; maxCounter), a new batch is claimed.</li>
 *   <li>The counter value is base62-encoded and left-padded to 8 characters.</li>
 * </ol>
 *
 * <h2>Why counter + batching beats hashing</h2>
 * <ul>
 *   <li><strong>No collision detection loop</strong> — hash-based approaches must retry on collision;
 *       counter values are guaranteed unique.</li>
 *   <li><strong>Predictable code length</strong> — base62(62^7) ≈ 3.5 trillion codes fit in 8 chars;
 *       hashes often need truncation and then collision checks.</li>
 *   <li><strong>Batching reduces Redis pressure</strong> — 1000 codes per Redis call means at
 *       ~1,400 writes/s peak only ~1.4 Redis INCRBY calls/s per instance (vs 1,400/s without batching).</li>
 *   <li><strong>Thread-safe without external locking</strong> — {@link AtomicLong} CAS handles
 *       intra-instance concurrency; only batch exhaustion requires the {@code synchronized} block.</li>
 * </ul>
 *
 * <h2>Capacity</h2>
 * 8 base62 chars support 62^8 ≈ 218 trillion unique codes — vastly exceeding the 1B target.
 * Even if the counter started at 0 and incremented at 10,000/s it would take ~690 years to wrap.
 *
 * <h2>Thread safety</h2>
 * {@code nextCode()} is safe for concurrent calls.  The {@code synchronized} in
 * {@link #claimBatch()} ensures only one thread issues the Redis INCRBY per batch boundary.
 * All other threads spin on the fast atomic path.
 */
@Component
@Slf4j
public class ShortCodeGenerator {

    /** Number of counter values claimed from Redis per round-trip. */
    static final long BATCH_SIZE = 1_000L;

    /** Redis key for the global monotonic counter. */
    static final String COUNTER_KEY = "url:counter";

    /**
     * Base62 character set: digits first, then lowercase, then uppercase.
     * Order matters — it defines what code "0" looks like (all zeros → "00000000").
     */
    static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static final int BASE = BASE62_CHARS.length(); // 62

    /** Target length for all generated codes; left-padded with '0' if shorter. */
    static final int CODE_LENGTH = 8;

    private final StringRedisTemplate redisTemplate;

    /**
     * Next counter value available for local use.  Starts at {@code Long.MAX_VALUE} to force
     * an immediate batch claim on the first call.
     */
    private final AtomicLong currentCounter = new AtomicLong(Long.MAX_VALUE);

    /**
     * Exclusive upper bound of the currently claimed batch.
     * Invariant: currentCounter &lt; maxCounter while the batch is valid.
     */
    private final AtomicLong maxCounter = new AtomicLong(0L);

    public ShortCodeGenerator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Returns the next unique 8-character base62 short code.
     *
     * <p>This method is thread-safe and lock-free on the hot path (when a batch is available).
     * It only acquires the instance-level monitor when a new batch must be claimed from Redis.
     *
     * @return an 8-character base62 string, e.g. {@code "00000001"}, {@code "0a3Zk8mQ"}
     */
    public String nextCode() {
        // Fast path: take the next value from the current batch.
        // We loop because multiple threads may simultaneously see a depleted batch and we
        // need each to re-check after claimBatch() completes.
        while (true) {
            long current = currentCounter.get();
            long max = maxCounter.get();

            if (current < max) {
                // Attempt a CAS to atomically claim this value.
                if (currentCounter.compareAndSet(current, current + 1)) {
                    return base62Encode(current);
                }
                // Another thread beat us; retry.
            } else {
                // Batch exhausted — claim a new one.
                claimBatch();
            }
        }
    }

    /**
     * Atomically claims the next batch of {@value #BATCH_SIZE} counter values from Redis.
     *
     * <p>The {@code synchronized} keyword ensures that only one thread performs the Redis
     * INCRBY at a time.  After it returns, the winning thread sets both {@code currentCounter}
     * and {@code maxCounter}; losing threads re-enter the {@link #nextCode()} loop and find a
     * valid batch already waiting.
     */
    private synchronized void claimBatch() {
        // Double-checked: another thread may have already claimed a batch by the time we
        // enter this synchronized block.
        if (currentCounter.get() < maxCounter.get()) {
            return;
        }

        Long newMax = redisTemplate.opsForValue().increment(COUNTER_KEY, BATCH_SIZE);
        if (newMax == null) {
            throw new IllegalStateException(
                "Redis INCRBY returned null for key '" + COUNTER_KEY + "'. " +
                "Check Redis connectivity and key type.");
        }

        long batchStart = newMax - BATCH_SIZE;
        log.debug("Claimed counter batch [{}, {}) from Redis", batchStart, newMax);

        // Update maxCounter first, then currentCounter, to avoid a window where another
        // thread sees currentCounter < maxCounter with a stale maxCounter.
        maxCounter.set(newMax);
        currentCounter.set(batchStart);
    }

    /**
     * Converts a non-negative long counter value to a base62 string, left-padded with
     * {@code '0'} to exactly {@value #CODE_LENGTH} characters.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code base62Encode(0)} → {@code "00000000"}</li>
     *   <li>{@code base62Encode(1)} → {@code "00000001"}</li>
     *   <li>{@code base62Encode(3521614606207L)} → {@code "zzzzzzzz"}</li>
     * </ul>
     *
     * @param value a non-negative counter value
     * @return the base62-encoded, padded string
     */
    String base62Encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Counter value must be non-negative, got: " + value);
        }
        if (value == 0) {
            return "0".repeat(CODE_LENGTH);
        }

        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        long remaining = value;
        while (remaining > 0) {
            sb.append(BASE62_CHARS.charAt((int) (remaining % BASE)));
            remaining /= BASE;
        }
        // sb is in reverse order — reverse it
        sb.reverse();

        // Left-pad with '0' if shorter than CODE_LENGTH
        while (sb.length() < CODE_LENGTH) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }
}
