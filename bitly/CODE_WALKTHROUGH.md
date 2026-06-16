# Bitly URL Shortener — Code Walkthrough

> A guided tour of the codebase for someone seeing it for the first time. Each section explains **what the code does and why it was written that way.**

---

## Suggested Reading Order

If you're starting from scratch, read files in this order:

1. `model/Url.java` — the data model everything else is built around
2. `repository/UrlRepository.java` — how we talk to PostgreSQL
3. `service/ShortCodeGenerator.java` — the most interesting algorithm in the codebase
4. `cache/UrlCacheService.java` — the Redis layer
5. `service/UrlShorteningService.java` — the write path
6. `service/UrlRedirectService.java` — the read path
7. `controller/UrlController.java` — the write endpoint
8. `controller/RedirectController.java` — the redirect endpoint
9. `exception/GlobalExceptionHandler.java` — how errors become HTTP responses
10. `config/` — application configuration
11. `src/test/` — how everything is tested

---

## Package Map

```
com.systemdesign.bitly/
│
├── BitlyApplication.java           Entry point — starts the Spring Boot app
│
├── model/
│   ├── Url.java                    JPA entity — maps to the `urls` PostgreSQL table
│   ├── ShortenRequest.java         DTO — what the client sends in POST /urls
│   └── ShortenResponse.java        DTO — what we return from POST /urls
│
├── repository/
│   └── UrlRepository.java          Spring Data JPA — SQL queries to PostgreSQL
│
├── service/
│   ├── ShortCodeGenerator.java     Core algorithm — counter+Redis batching → base62 codes
│   ├── UrlShorteningService.java   Write path orchestrator — validate → generate → save
│   └── UrlRedirectService.java     Read path orchestrator — cache → DB fallback
│
├── cache/
│   └── UrlCacheService.java        Redis cache — get / put / evict with TTL
│
├── controller/
│   ├── UrlController.java          POST /urls endpoint
│   └── RedirectController.java     GET /{shortCode} endpoint
│
├── exception/
│   ├── UrlNotFoundException.java   Thrown when short code doesn't exist → 404
│   ├── UrlExpiredException.java    Thrown when short code is past expiry → 410
│   ├── AliasAlreadyExistsException.java  Thrown on custom alias conflict → 409
│   └── GlobalExceptionHandler.java Turns every exception into an RFC 7807 Problem Detail
│
└── config/
    ├── AppProperties.java           Type-safe binding for `app.*` in application.yml
    ├── RedisConfig.java             StringRedisTemplate bean
    └── AppConfig.java               OpenAPI / Swagger UI metadata
```

---

## The Data Model — `model/Url.java`

This is the heart of the persistence layer. Let's read through the key parts:

```java
@Entity
@Table(name = "urls", indexes = {
    @Index(name = "idx_urls_short_code", columnList = "short_code", unique = true)
})
public class Url {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", length = 100, nullable = false, unique = true)
    private String shortCode;

    @Column(name = "long_url", nullable = false, columnDefinition = "TEXT")
    private String longUrl;

    @Column(name = "expiration_date")
    private LocalDateTime expirationDate;   // nullable — most URLs never expire

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return expirationDate != null && LocalDateTime.now().isAfter(expirationDate);
    }
}
```

**Why `id` exists but is never used for lookups:**  
The `id` (BIGSERIAL) is a surrogate key for JPA internals. Every redirect lookup uses `short_code`, not `id`. This is intentional — if we ever sharded the DB by `short_code`, the surrogate key would mean nothing cross-shard, but `short_code` always does.

**Why `long_url` is `TEXT` not `VARCHAR`:**  
PostgreSQL `VARCHAR(n)` has a hard limit. URLs with OAuth `redirect_uri` parameters or marketing query strings can exceed 2,000 characters. `TEXT` has no practical limit in PostgreSQL.

**Why `expiration_date` is nullable:**  
The vast majority of URLs never expire. A NOT NULL column with a far-future sentinel value (e.g. year 9999) wastes storage and complicates queries. Null means "never expires."

**Why `@PrePersist` sets `createdAt`:**  
Rather than relying on application code to set this in every call site, we use a JPA lifecycle hook. It's impossible to accidentally omit it.

**The `isExpired()` helper:**  
Used by `UrlRedirectService` to check expiry after a DB lookup. Keeps the expiry logic in one place (the entity), not scattered across services.

---

## The Repository — `repository/UrlRepository.java`

```java
public interface UrlRepository extends JpaRepository<Url, Long> {
    Optional<Url> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);
}
```

**Why Spring Data JPA?**  
At 12 writes/second, the ORM overhead is negligible. Spring Data gives us `findByShortCode` for free — it generates `SELECT * FROM urls WHERE short_code = ?` with the unique index hit.

**Why `Optional<Url>` return type?**  
Forces the caller to explicitly handle the "not found" case. This is how `UrlRedirectService` chains into a `UrlNotFoundException` cleanly.

---

## The Core Algorithm — `service/ShortCodeGenerator.java`

This is the most interesting class. Read it carefully.

### The Problem It Solves

We need to generate millions of unique 8-character codes across multiple concurrent app servers without collisions and without a database round-trip on every write.

### The Solution: Counter + Redis Batching

```java
static final long BATCH_SIZE = 1_000L;
static final String COUNTER_KEY = "url:counter";

private final AtomicLong currentCounter = new AtomicLong(Long.MAX_VALUE); // forces immediate batch claim
private final AtomicLong maxCounter     = new AtomicLong(0L);
```

**Why initialise `currentCounter` to `Long.MAX_VALUE`?**  
So the very first call to `nextCode()` sees `current >= max` (MAX_VALUE >= 0) and immediately claims a batch from Redis. It's an elegant trick to avoid a separate "initialised" flag.

### The Hot Path — `nextCode()`

```java
public String nextCode() {
    while (true) {
        long current = currentCounter.get();
        long max     = maxCounter.get();

        if (current < max) {
            if (currentCounter.compareAndSet(current, current + 1)) {
                return base62Encode(current);
            }
            // Another thread won the CAS — retry
        } else {
            claimBatch();  // batch exhausted, get more from Redis
        }
    }
}
```

**The CAS (Compare-And-Set) loop:**  
Multiple threads call `nextCode()` concurrently. Without a lock, two threads could read the same `current` and try to claim the same counter value. The `compareAndSet` only succeeds for one of them — the loser retries. This is lock-free (no blocking, no thread suspension).

### Claiming a New Batch — `claimBatch()`

```java
private synchronized void claimBatch() {
    if (currentCounter.get() < maxCounter.get()) {
        return;  // double-check: another thread already claimed a batch
    }
    Long newMax = redisTemplate.opsForValue().increment(COUNTER_KEY, BATCH_SIZE);
    // newMax = e.g. 3000 means this instance owns [2001, 3000]
    maxCounter.set(newMax);
    currentCounter.set(newMax - BATCH_SIZE);
}
```

**Why `synchronized` here but not in `nextCode()`?**  
`nextCode()` is the hot path — 99.9% of calls never reach `claimBatch()`. Making it lock-free keeps it fast. `claimBatch()` is called at most once per 1,000 writes — the `synchronized` penalty is insignificant.

**Why the double-check inside `claimBatch()`?**  
Suppose 3 threads all simultaneously find the batch exhausted and enter `claimBatch()`. The first thread takes the lock, claims a batch, and exits. Threads 2 and 3 are now in the lock queue. When thread 2 gets the lock, the batch is already full — the double-check catches this and returns immediately without another Redis call.

**Multi-server uniqueness:**
```
App Server A: INCRBY 1000 → gets back 1000  → owns [1,    1000]
App Server B: INCRBY 1000 → gets back 2000  → owns [1001, 2000]
App Server C: INCRBY 1000 → gets back 3000  → owns [2001, 3000]
```
Redis `INCRBY` is atomic. No two servers ever get the same range.

### Base62 Encoding — `base62Encode()`

```java
static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

String base62Encode(long value) {
    if (value == 0) return "0".repeat(CODE_LENGTH);
    StringBuilder sb = new StringBuilder(CODE_LENGTH);
    long remaining = value;
    while (remaining > 0) {
        sb.append(BASE62_CHARS.charAt((int)(remaining % 62)));
        remaining /= 62;
    }
    sb.reverse();
    while (sb.length() < CODE_LENGTH) sb.insert(0, '0');
    return sb.toString();
}
```

**Why not use `Long.toString(value, 62)`?**  
Java's `Long.toString` uses its own character ordering. We want a specific set (`0-9a-zA-Z`) to match the URL-safe characters and produce human-readable codes.

**The build-then-reverse pattern:**  
The modulo operation naturally produces digits in reverse order (least significant first). We collect them, then reverse, then pad. Classic base conversion.

---

## The Cache Layer — `cache/UrlCacheService.java`

```java
static final String KEY_PREFIX = "url:";

public Optional<String> get(String shortCode) {
    try {
        return Optional.ofNullable(
            redisTemplate.opsForValue().get("url:" + shortCode)
        );
    } catch (Exception e) {
        log.warn("Redis GET failed, falling back to DB: {}", e.getMessage());
        return Optional.empty();
    }
}

public void put(String shortCode, String longUrl, Duration ttl) {
    try {
        redisTemplate.opsForValue().set("url:" + shortCode, longUrl, ttl);
    } catch (Exception e) {
        log.warn("Redis SET failed: {}", e.getMessage());
    }
}
```

**Why `StringRedisTemplate` instead of `RedisTemplate<String, Object>`?**  
We only store plain strings (short codes → URLs). `StringRedisTemplate` skips Java object serialisation entirely — keys and values are stored as raw UTF-8 bytes. This uses ~60% less memory than serialised objects.

**Why wrap every Redis call in try/catch?**  
Redis is a cache, not the source of truth. If Redis is unavailable (failover, network blip), the system should degrade to DB-only mode — not crash. Every `get()` failure returns `Optional.empty()`, which the calling service interprets as a cache miss and hits the DB instead.

**Why the `url:` prefix on keys?**  
`ShortCodeGenerator` also uses a Redis key: `url:counter`. The prefix namespace ensures cache keys (`url:0a3Zk8mQ`) never collide with the counter key. It also makes it easy to flush just cache entries in Redis ops: `SCAN 0 MATCH url:* COUNT 100`.

**Why not `@Cacheable`?**  
Spring's `@Cacheable` does not support per-entry TTL computed from runtime values. We need each URL's cache TTL to align with its expiration date — that's a dynamic value. Explicit `put(shortCode, longUrl, ttl)` gives us that control.

---

## The Write Path — `service/UrlShorteningService.java`

```java
@Transactional
public ShortenResponse shorten(ShortenRequest request) {
    String shortCode = resolveShortCode(request);   // alias or generated

    Url url = Url.builder()
        .shortCode(shortCode)
        .longUrl(request.getLongUrl())
        .customAlias(request.getCustomAlias())
        .expirationDate(request.getExpirationDate())
        .build();

    Url saved = urlRepository.save(url);          // INSERT into PostgreSQL
    urlCacheService.evict(shortCode);             // defensive eviction
    return buildResponse(saved);
}
```

**`resolveShortCode()` — custom alias vs generated:**
```java
private String resolveShortCode(ShortenRequest request) {
    String alias = request.getCustomAlias();
    if (alias != null && !alias.isBlank()) {
        if (urlRepository.existsByShortCode(alias)) {
            throw new AliasAlreadyExistsException(alias);  // → 409 Conflict
        }
        return alias;
    }
    return shortCodeGenerator.nextCode();  // no DB call needed
}
```
Two code paths, clearly separated. The alias path does a DB check (necessary — aliases aren't managed by our counter). The generated path does zero DB or Redis calls until the INSERT.

**Why evict after save instead of put?**  
We evict rather than populate the cache immediately after creating a URL. Reason: the URL might never be accessed — most short links are never clicked. Warming the cache proactively would waste Redis memory. The cache-aside pattern means the cache warms naturally on first access.

**`computeCacheTtl()` — shared between write and read services:**
```java
Duration computeCacheTtl(LocalDateTime expirationDate) {
    if (expirationDate == null) return Duration.ofSeconds(defaultTtl);
    long secondsLeft = Duration.between(LocalDateTime.now(), expirationDate).getSeconds();
    if (secondsLeft <= 0) return Duration.ofSeconds(1);  // already expired
    return Duration.ofSeconds(Math.min(secondsLeft, defaultTtl));
}
```
This is `package-private` so `UrlRedirectService` can call it when warming the cache after a DB miss. Having the TTL logic in one place means we can't have the write and read paths computing different TTLs for the same URL.

---

## The Read Path — `service/UrlRedirectService.java`

```java
@Transactional(readOnly = true)
public String resolveUrl(String shortCode) {
    return urlCacheService.get(shortCode)
        .map(longUrl -> {
            log.debug("Cache HIT: shortCode={}", shortCode);
            return longUrl;
        })
        .orElseGet(() -> resolveFromDatabase(shortCode));
}

private String resolveFromDatabase(String shortCode) {
    Url url = urlRepository.findByShortCode(shortCode)
        .orElseThrow(() -> new UrlNotFoundException(shortCode));

    if (url.isExpired()) {
        throw new UrlExpiredException(shortCode, url.getExpirationDate());
    }

    Duration ttl = urlShorteningService.computeCacheTtl(url.getExpirationDate());
    urlCacheService.put(shortCode, url.getLongUrl(), ttl);
    return url.getLongUrl();
}
```

**The `Optional` chain:**  
`urlCacheService.get()` returns `Optional<String>`. We use `.map()` for the hit case and `.orElseGet()` for the miss. This is idiomatic Java and avoids null checks — the compiler enforces that both cases are handled.

**Why `@Transactional(readOnly = true)`?**  
Marks the transaction as read-only, which:
1. Prevents Hibernate from doing dirty-checking (unnecessary for reads)
2. Allows the JDBC driver / connection pool to route to a read replica if configured

**Expiry check after DB lookup, not cache:**  
We never cache expired URLs. When a URL is found in the DB but `isExpired()` is true, we throw `UrlExpiredException` and return 410 — without caching anything. If we cached expired URLs, a race condition could serve a 302 redirect briefly after expiry.

---

## The Controllers

### `UrlController.java` — POST /urls

```java
@PostMapping(consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
public ResponseEntity<ShortenResponse> createShortUrl(@Valid @RequestBody ShortenRequest request) {
    ShortenResponse response = urlShorteningService.shorten(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

**`@Valid`** triggers Bean Validation on `ShortenRequest` before the method body runs. If validation fails, Spring throws `MethodArgumentNotValidException`, which `GlobalExceptionHandler` catches and returns as a 422.

**Why `201 Created` and not `200 OK`?**  
HTTP 201 specifically means "a new resource was created." The `Location` header would conventionally point to the created resource — here `short_url` in the body serves that purpose.

### `RedirectController.java` — GET /{shortCode}

```java
@GetMapping("/{shortCode:[a-zA-Z0-9_-]+}")
public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
    String longUrl = urlRedirectService.resolveUrl(shortCode);
    HttpHeaders headers = new HttpHeaders();
    headers.setLocation(new URI(longUrl));
    return new ResponseEntity<>(headers, HttpStatus.FOUND);  // 302
}
```

**The regex in the path `[a-zA-Z0-9_-]+`:**  
This is a Spring MVC path variable constraint. Requests like `GET /../../etc/passwd` or `GET /foo;bar` don't even reach the service layer — they return 404 at the routing level. This is an input validation defence-in-depth layer.

**Why `ResponseEntity<Void>` and not `void`?**  
Redirects have no body. `ResponseEntity<Void>` lets us set response headers (specifically `Location`) while returning no body. `void` return type in Spring MVC would not allow us to set headers manually.

**Why `HttpStatus.FOUND` (302) and not `HttpStatus.MOVED_PERMANENTLY` (301)?**  
301 is cached permanently by browsers. Once a browser caches a 301, it never asks the server again — making it impossible to:
- Update where a link points
- Honour an expiration date
- Collect click analytics

302 causes every click to pass through our servers.

---

## Error Handling — `exception/GlobalExceptionHandler.java`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UrlNotFoundException.class)
    public ProblemDetail handleNotFound(UrlNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://bitly.example.com/errors/url-not-found"));
        pd.setProperty("short_code", ex.getShortCode());
        return pd;
    }
    // ... similar for UrlExpiredException (410), AliasAlreadyExistsException (409)
}
```

**Why `@RestControllerAdvice`?**  
It intercepts exceptions thrown anywhere in a `@Controller` or `@RestController`. Without this, unhandled exceptions become ugly 500s with a Spring whitepage error. This class gives us a single place to control every error response format.

**Why RFC 7807 Problem Detail?**  
`ProblemDetail` is a standardised error response format (RFC 7807). Instead of ad-hoc `{"error": "not found"}`, clients get a machine-readable structure with a `type` URI, `title`, `status`, `detail`, and optional extension properties. Spring Boot 3.x supports it natively.

**The exception → HTTP status mapping:**
```
UrlNotFoundException        → 404 Not Found
UrlExpiredException         → 410 Gone
AliasAlreadyExistsException → 409 Conflict
MethodArgumentNotValidException → 422 Unprocessable Entity
ResponseStatusException     → passthrough (whatever status it carries)
Exception (catch-all)       → 500 Internal Server Error
```

**Why handle `ErrorResponse` in the catch-all?**  
Spring MVC 6.1+ introduced some exceptions that implement `ErrorResponse` but don't extend `ResponseStatusException` (e.g. `NoResourceFoundException` for 404 on unknown paths). The `instanceof ErrorResponse` check at the bottom of the catch-all handles these gracefully.

---

## Configuration — `config/`

### `AppProperties.java`
```java
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String baseUrl;
    private Cache cache = new Cache();

    public static class Cache {
        private long defaultTtlSeconds = 86400;
    }
}
```
Binds `app.base-url` and `app.cache.default-ttl-seconds` from `application.yml`. Type-safe — no `@Value("${app.base-url}")` strings scattered around the codebase.

### `RedisConfig.java`
```java
@Bean
public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
    return new StringRedisTemplate(factory);
}
```
Explicit bean declaration ensures we use `StringRedisTemplate` (plain string serialisation) everywhere. If we used `RedisTemplate<Object, Object>` by default, keys and values would be Java-serialised — incompatible with Redis CLI inspection and other clients.

### `application.yml` — Key Settings Explained

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate     # ← Hibernate checks schema matches entities at startup
  sql:
    init:
      mode: always           # ← schema.sql runs on every startup (idempotent DDL)

  data:
    redis:
      timeout: 500ms         # ← Redis command timeout — limits latency impact if Redis degrades

server:
  tomcat:
    threads:
      max: 200               # ← High thread count for redirect burst (1000:1 read:write)
```

**`ddl-auto: validate`** — Hibernate does NOT auto-create or modify tables. It only checks that the DB schema matches the entity definitions. If they diverge, startup fails loudly rather than silently. `schema.sql` is the authoritative source of schema.

**Redis `timeout: 500ms`** — If Redis takes more than 500ms to respond, the call fails fast and falls back to DB. Without this, a slow Redis could cause every redirect to hang, violating the 100ms SLA.

---

## Test Structure — `src/test/`

```
test/
├── service/
│   ├── ShortCodeGeneratorTest.java  — unit tests for the base62 algorithm and batch logic
│   ├── UrlShorteningServiceTest.java — write path with mocked DB and Redis
│   └── UrlRedirectServiceTest.java  — read path: cache hit, miss, not found, expired
└── controller/
    ├── UrlControllerTest.java       — Spring MVC slice test for POST /urls
    └── RedirectControllerTest.java  — Spring MVC slice test for GET /{shortCode}
```

**Service tests use Mockito:**  
`@ExtendWith(MockitoExtension.class)` + `@Mock` for `UrlRepository`, `StringRedisTemplate`, etc. No Spring context needed — these are pure unit tests that run in milliseconds.

**Controller tests use `@WebMvcTest`:**  
`@WebMvcTest(UrlController.class)` loads only the web layer (controllers, exception handlers, filters) — not the full Spring context. Services are mocked with `@MockBean`. This keeps tests fast while still exercising Spring MVC request parsing, validation, and response serialisation.

**`ShortCodeGeneratorTest` includes a concurrency test:**
```java
@Test
void testConcurrentUniqueness() throws Exception {
    // 10 threads × 500 codes each = 5,000 codes
    // All must be unique
}
```
This test catches bugs in the CAS loop and batch boundary logic that single-threaded tests would miss.

---

## Dependency Flow Diagram

```
UrlController
    └── UrlShorteningService
            ├── ShortCodeGenerator ──── StringRedisTemplate (Redis counter)
            ├── UrlRepository ────────── PostgreSQL
            └── UrlCacheService ──────── StringRedisTemplate (Redis cache)

RedirectController
    └── UrlRedirectService
            ├── UrlCacheService ──────── StringRedisTemplate (Redis cache)
            ├── UrlRepository ────────── PostgreSQL
            └── UrlShorteningService ─── (TTL computation only, no DB call)

GlobalExceptionHandler
    └── (intercepts exceptions from both controller stacks)
```

**Observations:**
- Controllers are thin — they only parse/validate input and format output.
- Services are the only place with business logic.
- `UrlCacheService` and `UrlRepository` are pure infrastructure adapters — no business logic.
- `UrlRedirectService` depends on `UrlShorteningService` only for the `computeCacheTtl()` utility. This is slightly unusual (service depending on another service) but avoids duplicating TTL logic.

---

## Key Invariants to Know

1. **Every `shortCode` in the DB is unique** — enforced by both the application (`existsByShortCode` check, `ShortCodeGenerator` uniqueness) and the DB (`UNIQUE` constraint). Defense in depth.

2. **The cache never outlives a URL's expiration** — `computeCacheTtl()` always sets a TTL ≤ seconds until expiry. After expiry, the cache entry is gone and the DB returns 410.

3. **Redis failure never causes a 500** — every Redis call in `UrlCacheService` is wrapped in try/catch and degrades to `Optional.empty()` (miss) or silent failure (put/evict).

4. **Counter values are never reused** — Redis `INCRBY` is monotonically increasing and atomic. Even if an app server crashes mid-batch, those counter values are lost (gap in codes), not reused.

5. **Writes are idempotent at the DB level** — the `UNIQUE` constraint on `short_code` means a duplicate INSERT fails rather than silently succeeding. The app catches this as a 409 for custom aliases; generated codes never collide.
