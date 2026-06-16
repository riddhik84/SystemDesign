package com.systemdesign.bitly.service;

import com.systemdesign.bitly.cache.UrlCacheService;
import com.systemdesign.bitly.config.AppProperties;
import com.systemdesign.bitly.exception.UrlExpiredException;
import com.systemdesign.bitly.exception.UrlNotFoundException;
import com.systemdesign.bitly.model.Url;
import com.systemdesign.bitly.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlRedirectService")
class UrlRedirectServiceTest {

    @Mock private UrlCacheService urlCacheService;
    @Mock private UrlRepository urlRepository;

    private UrlShorteningService urlShorteningService;
    private UrlRedirectService redirectService;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setBaseUrl("http://localhost:8080");
        props.setCache(new AppProperties.Cache());
        // We use the real UrlShorteningService for TTL computation (no Redis needed — it's only
        // used for shortCode generation which is not exercised in redirect tests).
        urlShorteningService = new UrlShorteningService(urlRepository, null, urlCacheService, props);
        redirectService = new UrlRedirectService(urlCacheService, urlRepository, urlShorteningService);
    }

    // -------------------------------------------------------------------------
    // Cache hit
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("returns cached long URL on cache hit without hitting DB")
    void returnsCachedUrl_onCacheHit() {
        when(urlCacheService.get("abc12345")).thenReturn(Optional.of("https://example.com"));

        String result = redirectService.resolveUrl("abc12345");

        assertThat(result).isEqualTo("https://example.com");
        verify(urlRepository, never()).findByShortCode(anyString());
        verify(urlCacheService, never()).put(anyString(), anyString(), any());
    }

    // -------------------------------------------------------------------------
    // Cache miss → DB hit
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("falls back to DB on cache miss and warms the cache")
    void fallsBackToDb_andWarmsCache() {
        when(urlCacheService.get("abc12345")).thenReturn(Optional.empty());
        Url url = activeUrl("abc12345", "https://example.com", null);
        when(urlRepository.findByShortCode("abc12345")).thenReturn(Optional.of(url));

        String result = redirectService.resolveUrl("abc12345");

        assertThat(result).isEqualTo("https://example.com");
        verify(urlCacheService).put(eq("abc12345"), eq("https://example.com"), any(Duration.class));
    }

    // -------------------------------------------------------------------------
    // Not found
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("throws UrlNotFoundException when short code not in DB")
    void throwsNotFound_whenCodeMissing() {
        when(urlCacheService.get("missing1")).thenReturn(Optional.empty());
        when(urlRepository.findByShortCode("missing1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> redirectService.resolveUrl("missing1"))
            .isInstanceOf(UrlNotFoundException.class)
            .hasMessageContaining("missing1");
    }

    // -------------------------------------------------------------------------
    // Expired URL
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("throws UrlExpiredException when URL is past its expiration date")
    void throwsExpired_whenUrlExpired() {
        when(urlCacheService.get("expired1")).thenReturn(Optional.empty());
        LocalDateTime pastExpiry = LocalDateTime.now().minusHours(1);
        Url url = activeUrl("expired1", "https://example.com", pastExpiry);
        when(urlRepository.findByShortCode("expired1")).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> redirectService.resolveUrl("expired1"))
            .isInstanceOf(UrlExpiredException.class)
            .hasMessageContaining("expired1");

        // Must NOT cache expired URLs
        verify(urlCacheService, never()).put(anyString(), anyString(), any());
    }

    // -------------------------------------------------------------------------
    // Non-expiring URL cache TTL
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("uses default TTL when URL has no expiration date")
    void usesDefaultTtl_whenNoExpiration() {
        when(urlCacheService.get("code0001")).thenReturn(Optional.empty());
        Url url = activeUrl("code0001", "https://example.com", null);
        when(urlRepository.findByShortCode("code0001")).thenReturn(Optional.of(url));

        redirectService.resolveUrl("code0001");

        verify(urlCacheService).put(eq("code0001"), eq("https://example.com"),
            eq(Duration.ofSeconds(86_400L)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Url activeUrl(String shortCode, String longUrl, LocalDateTime expiry) {
        Url url = new Url();
        url.setShortCode(shortCode);
        url.setLongUrl(longUrl);
        url.setExpirationDate(expiry);
        url.setCreatedAt(LocalDateTime.now());
        return url;
    }
}
