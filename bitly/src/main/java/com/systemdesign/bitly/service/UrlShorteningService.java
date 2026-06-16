package com.systemdesign.bitly.service;

import com.systemdesign.bitly.cache.UrlCacheService;
import com.systemdesign.bitly.config.AppProperties;
import com.systemdesign.bitly.exception.AliasAlreadyExistsException;
import com.systemdesign.bitly.model.ShortenRequest;
import com.systemdesign.bitly.model.ShortenResponse;
import com.systemdesign.bitly.model.Url;
import com.systemdesign.bitly.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Orchestrates the URL write path: validate → generate short code → persist → return response.
 *
 * <h2>Write path sequence</h2>
 * <pre>
 * Client
 *   └─▶ UrlController.createShortUrl()
 *         └─▶ UrlShorteningService.shorten()
 *               ├─ (custom alias?) validate uniqueness via UrlRepository
 *               ├─ (no alias)     ShortCodeGenerator.nextCode()  [counter batch, no DB call]
 *               ├─ UrlRepository.save()                          [single INSERT]
 *               └─ UrlCacheService.evict()                       [defensive pre-eviction]
 * </pre>
 *
 * <h2>Idempotency note</h2>
 * This service does not deduplicate identical long URLs.  Two requests with the same long URL
 * receive two different short codes.  Deduplication would require an indexed lookup by
 * {@code long_url} (a TEXT column), which is expensive at scale.  The trade-off is intentional
 * and documented in DESIGN.md.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrlShorteningService {

    private final UrlRepository urlRepository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final UrlCacheService urlCacheService;
    private final AppProperties appProperties;

    /**
     * Creates a new shortened URL.
     *
     * @param request the client request containing the long URL, optional alias, and optional expiry
     * @return response containing the short URL and metadata
     * @throws AliasAlreadyExistsException if {@code customAlias} is provided but already taken
     */
    @Transactional
    public ShortenResponse shorten(ShortenRequest request) {
        String shortCode = resolveShortCode(request);

        Url url = Url.builder()
            .shortCode(shortCode)
            .longUrl(request.getLongUrl())
            .customAlias(request.getCustomAlias())
            .expirationDate(request.getExpirationDate())
            .build();

        Url saved = urlRepository.save(url);
        log.info("Created short URL: shortCode={}, longUrl={}", shortCode, request.getLongUrl());

        // Pre-emptively evict any stale cache entry for this short code (e.g. a previously
        // expired record that shared the same custom alias and was since deleted).
        urlCacheService.evict(shortCode);

        return buildResponse(saved);
    }

    /**
     * Determines the short code to use.
     * <ul>
     *   <li>If the request has a {@code customAlias}: validate it is available, then use it.</li>
     *   <li>Otherwise: generate one via the counter-batch generator.</li>
     * </ul>
     */
    private String resolveShortCode(ShortenRequest request) {
        String alias = request.getCustomAlias();
        if (alias != null && !alias.isBlank()) {
            if (urlRepository.existsByShortCode(alias)) {
                throw new AliasAlreadyExistsException(alias);
            }
            log.debug("Using custom alias as short code: {}", alias);
            return alias;
        }
        String generated = shortCodeGenerator.nextCode();
        log.debug("Generated short code: {}", generated);
        return generated;
    }

    /**
     * Builds the response DTO from the persisted entity, prepending the configured base URL.
     */
    private ShortenResponse buildResponse(Url saved) {
        String baseUrl = appProperties.getBaseUrl();
        // Ensure no double-slash between base URL and short code
        String separator = baseUrl.endsWith("/") ? "" : "/";
        String shortUrl = baseUrl + separator + saved.getShortCode();

        return ShortenResponse.builder()
            .shortUrl(shortUrl)
            .shortCode(saved.getShortCode())
            .longUrl(saved.getLongUrl())
            .expirationDate(saved.getExpirationDate())
            .createdAt(saved.getCreatedAt())
            .build();
    }

    /**
     * Computes the Redis TTL for a URL.
     * <ul>
     *   <li>No expiration → use the configured default TTL.</li>
     *   <li>Has expiration → use the remaining time (capped at default to prevent very long TTLs
     *       that would pin memory in Redis).</li>
     * </ul>
     *
     * <p>This utility is package-private so {@link UrlRedirectService} can reuse it when
     * warming the cache after a DB lookup.
     */
    Duration computeCacheTtl(LocalDateTime expirationDate) {
        long defaultSeconds = appProperties.getCache().getDefaultTtlSeconds();
        if (expirationDate == null) {
            return Duration.ofSeconds(defaultSeconds);
        }
        long secondsUntilExpiry = Duration.between(LocalDateTime.now(), expirationDate).getSeconds();
        if (secondsUntilExpiry <= 0) {
            // Already expired — TTL of 1 second causes immediate Redis expiry.
            return Duration.ofSeconds(1);
        }
        return Duration.ofSeconds(Math.min(secondsUntilExpiry, defaultSeconds));
    }
}
