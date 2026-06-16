package com.systemdesign.bitly.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShortCodeGenerator}.
 *
 * <p>Redis is mocked so tests run without infrastructure dependencies.
 */
@DisplayName("ShortCodeGenerator")
class ShortCodeGeneratorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private ShortCodeGenerator generator;

    /** Simulates the Redis INCRBY counter. Starts at 0; increments by BATCH_SIZE each call. */
    private final AtomicLong redisCounter = new AtomicLong(0L);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // Each call to increment(key, BATCH_SIZE) advances the simulated counter
        when(valueOps.increment(eq(ShortCodeGenerator.COUNTER_KEY), anyLong()))
            .thenAnswer(inv -> redisCounter.addAndGet(inv.getArgument(1)));

        generator = new ShortCodeGenerator(redisTemplate);
    }

    // -------------------------------------------------------------------------
    // base62Encode
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("base62Encode()")
    class Base62EncodeTests {

        @Test
        @DisplayName("encodes 0 as 8 zeros")
        void encodeZero() {
            assertThat(generator.base62Encode(0)).isEqualTo("00000000");
        }

        @Test
        @DisplayName("encodes 1 as '00000001'")
        void encodeOne() {
            assertThat(generator.base62Encode(1)).isEqualTo("00000001");
        }

        @ParameterizedTest(name = "base62Encode({0}) == {1}")
        @CsvSource({
            "61,   '0000000Z'",
            "62,   '00000010'",
            "3843, '000000ZZ'",
            "3844, '00000100'"
        })
        @DisplayName("encodes boundary values correctly")
        void encodeBoundaries(long value, String expected) {
            assertThat(generator.base62Encode(value)).isEqualTo(expected.trim().replace("'", ""));
        }

        @Test
        @DisplayName("output is always exactly 8 characters")
        void alwaysEightChars() {
            for (long v : new long[]{0, 1, 61, 62, 3843, 3844, 1_000_000L, 999_999_999L}) {
                assertThat(generator.base62Encode(v))
                    .as("base62Encode(%d)", v)
                    .hasSize(ShortCodeGenerator.CODE_LENGTH);
            }
        }

        @Test
        @DisplayName("output contains only base62 characters")
        void onlyBase62Chars() {
            Set<Character> allowed = new HashSet<>();
            for (char c : ShortCodeGenerator.BASE62_CHARS.toCharArray()) {
                allowed.add(c);
            }
            for (long v = 0; v < 10_000; v++) {
                for (char c : generator.base62Encode(v).toCharArray()) {
                    assertThat(allowed).contains(c);
                }
            }
        }

        @Test
        @DisplayName("throws on negative input")
        void throwsOnNegative() {
            assertThatThrownBy(() -> generator.base62Encode(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        }
    }

    // -------------------------------------------------------------------------
    // nextCode()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("nextCode()")
    class NextCodeTests {

        @Test
        @DisplayName("generates unique codes across two full batches")
        void uniqueAcrossBatches() {
            Set<String> codes = new HashSet<>();
            int count = (int) (ShortCodeGenerator.BATCH_SIZE * 2);
            for (int i = 0; i < count; i++) {
                codes.add(generator.nextCode());
            }
            assertThat(codes).hasSize(count);
        }

        @Test
        @DisplayName("codes are exactly 8 characters")
        void codesHaveCorrectLength() {
            for (int i = 0; i < 20; i++) {
                assertThat(generator.nextCode()).hasSize(ShortCodeGenerator.CODE_LENGTH);
            }
        }

        @Test
        @DisplayName("codes contain only base62 characters")
        void codesAreBase62() {
            String validChars = ShortCodeGenerator.BASE62_CHARS;
            for (int i = 0; i < 100; i++) {
                String code = generator.nextCode();
                for (char c : code.toCharArray()) {
                    assertThat(validChars).contains(String.valueOf(c));
                }
            }
        }

        @Test
        @DisplayName("generates unique codes within a single batch (no repeats)")
        void uniqueWithinBatch() {
            // Uniqueness is the critical invariant for a URL shortener.
            // Note: string lexicographic order does NOT equal numeric order for our base62 charset
            // ("0-9a-zA-Z") because '9' (ASCII 57) < 'A' (65) < 'a' (97), so we only assert
            // uniqueness here, not string ordering.
            int batchSize = (int) ShortCodeGenerator.BATCH_SIZE;
            Set<String> codes = new HashSet<>();
            for (int i = 0; i < batchSize; i++) {
                codes.add(generator.nextCode());
            }
            assertThat(codes).hasSize(batchSize);
        }
    }

    // -------------------------------------------------------------------------
    // Concurrency
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Thread safety")
    class ConcurrencyTests {

        @Test
        @DisplayName("produces unique codes under concurrent load")
        void concurrentUniqueness() throws InterruptedException {
            int threads = 10;
            int codesPerThread = 500;
            int total = threads * codesPerThread;

            Set<String> codes = java.util.Collections.synchronizedSet(new HashSet<>());
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < codesPerThread; i++) {
                            codes.add(generator.nextCode());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown(); // release all threads simultaneously
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(codes)
                .as("All %d codes must be unique", total)
                .hasSize(total);
        }
    }

    // -------------------------------------------------------------------------
    // Redis error handling
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Redis error handling")
    class RedisErrorTests {

        @Test
        @DisplayName("throws IllegalStateException when Redis returns null")
        void throwsOnNullFromRedis() {
            when(valueOps.increment(eq(ShortCodeGenerator.COUNTER_KEY), anyLong()))
                .thenReturn(null);

            // Force a fresh generator with no pre-claimed batch
            ShortCodeGenerator gen = new ShortCodeGenerator(redisTemplate);

            assertThatThrownBy(gen::nextCode)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis INCRBY returned null");
        }
    }
}
