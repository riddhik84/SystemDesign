package com.systemdesign.bitly.service;

import com.systemdesign.bitly.cache.UrlCacheService;
import com.systemdesign.bitly.exception.UrlExpiredException;
import com.systemdesign.bitly.exception.UrlNotFoundException;
import com.systemdesign.bitly.model.Url;
import com.systemdesign.bitly.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Orchestrates the URL read (redirect) path.
 *
 * <h2>Read path sequence</h2>
 * <pre>
 * Client
 *   └─▶ RedirectController.redirect()
 *         └─▶ UrlRedirectService.resolveUrl()
 *               ├─ UrlCacheService.get(shortCode)      [O(1) Redis GET]
 *               │     HIT ──▶ return longUrl
 *               │     MISS
 *               │       └─▶ UrlRepository.findByShortCode() [index scan on PG]
 *               │             NOT FOUND ──▶ throw UrlNotFoundException (404)
 *               │             FOUND but expired ──▶ throw UrlExpiredException (410)
 *               │             FOUND ──▶ UrlCacheService.put()  [warm cache]
 *               │                         return longUrl
 * </pre>
 *
 * <h2>Latency budget</h2>
 * <ul>
 *   <li>Cache hit: ~1ms (Redis round-trip within the same data centre)</li>
 *   <li>Cache miss: ~5ms (Redis miss + PostgreSQL index scan) — still well under 100ms target.</li>
 * </ul>
 *
 * <h2>Read-your-writes consistency</h2>
 * Because {@code shorten()} evicts the cache entry after every write, a client that immediately
 * resolves the code after creating it will always hit the DB and get the fresh record, then warm
 * the cache for all subsequent reads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrlRedirectService {

    private final UrlCacheService urlCacheService;
    private final UrlRepository urlRepository;
    private final UrlShorteningService urlShorteningService;

    /**
     * Resolves a short code to its original long URL, using the Redis cache as the primary
     * lookup and PostgreSQL as the fallback.
     *
     * @param shortCode the 8-char (or alias) short code from the request path
     * @return the original long URL to redirect to
     * @throws UrlNotFoundException if no record exists for this short code
     * @throws UrlExpiredException  if the record exists but has passed its expiration date
     */
    @Transactional(readOnly = true)
    public String resolveUrl(String shortCode) {
        // 1. Try Redis cache (hot path — expected ~99%+ hit rate after warmup)
        return urlCacheService.get(shortCode)
            .map(longUrl -> {
                log.debug("Redirect cache HIT: shortCode={}", shortCode);
                return longUrl;
            })
            .orElseGet(() -> resolveFromDatabase(shortCode));
    }

    /**
     * Performs the full database lookup, expiry check, and cache warm-up for a cache miss.
     */
    private String resolveFromDatabase(String shortCode) {
        log.debug("Redirect cache MISS: shortCode={}, querying DB", shortCode);

        Url url = urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (url.isExpired()) {
            // Do NOT cache expired URLs — they should return 410 consistently.
            throw new UrlExpiredException(shortCode, url.getExpirationDate());
        }

        // Warm the cache for subsequent requests
        Duration ttl = urlShorteningService.computeCacheTtl(url.getExpirationDate());
        urlCacheService.put(shortCode, url.getLongUrl(), ttl);

        log.debug("Redirect DB lookup success: shortCode={}, ttl={}", shortCode, ttl);
        return url.getLongUrl();
    }
}
