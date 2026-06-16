package com.systemdesign.bitly.service;

import com.systemdesign.bitly.cache.UrlCacheService;
import com.systemdesign.bitly.config.AppProperties;
import com.systemdesign.bitly.exception.AliasAlreadyExistsException;
import com.systemdesign.bitly.model.ShortenRequest;
import com.systemdesign.bitly.model.ShortenResponse;
import com.systemdesign.bitly.model.Url;
import com.systemdesign.bitly.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlShorteningService")
class UrlShorteningServiceTest {

    @Mock private UrlRepository urlRepository;
    @Mock private ShortCodeGenerator shortCodeGenerator;
    @Mock private UrlCacheService urlCacheService;

    private AppProperties appProperties;
    private UrlShorteningService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setBaseUrl("http://localhost:8080");
        appProperties.setCache(new AppProperties.Cache());

        service = new UrlShorteningService(urlRepository, shortCodeGenerator, urlCacheService, appProperties);
    }

    // -------------------------------------------------------------------------
    // Generated code path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("uses generated short code when no alias provided")
    void generatesCode_whenNoAlias() {
        when(shortCodeGenerator.nextCode()).thenReturn("abc12345");
        Url saved = urlWith("abc12345", "https://example.com", null, null);
        when(urlRepository.save(any())).thenReturn(saved);

        ShortenResponse response = service.shorten(
            ShortenRequest.builder().longUrl("https://example.com").build()
        );

        assertThat(response.getShortCode()).isEqualTo("abc12345");
        assertThat(response.getShortUrl()).isEqualTo("http://localhost:8080/abc12345");
        verify(shortCodeGenerator).nextCode();
        verify(urlRepository, never()).existsByShortCode(anyString());
    }

    @Test
    @DisplayName("persists entity with correct fields")
    void persistsCorrectEntity() {
        when(shortCodeGenerator.nextCode()).thenReturn("xyz00001");
        LocalDateTime expiry = LocalDateTime.now().plusDays(7);
        Url saved = urlWith("xyz00001", "https://example.com/long", null, expiry);
        when(urlRepository.save(any())).thenReturn(saved);

        service.shorten(ShortenRequest.builder()
            .longUrl("https://example.com/long")
            .expirationDate(expiry)
            .build());

        ArgumentCaptor<Url> captor = ArgumentCaptor.forClass(Url.class);
        verify(urlRepository).save(captor.capture());
        Url persisted = captor.getValue();
        assertThat(persisted.getShortCode()).isEqualTo("xyz00001");
        assertThat(persisted.getLongUrl()).isEqualTo("https://example.com/long");
        assertThat(persisted.getExpirationDate()).isEqualTo(expiry);
    }

    @Test
    @DisplayName("evicts cache after saving")
    void evictsCacheAfterSave() {
        when(shortCodeGenerator.nextCode()).thenReturn("code0001");
        Url saved = urlWith("code0001", "https://example.com", null, null);
        when(urlRepository.save(any())).thenReturn(saved);

        service.shorten(ShortenRequest.builder().longUrl("https://example.com").build());

        verify(urlCacheService).evict("code0001");
    }

    // -------------------------------------------------------------------------
    // Custom alias path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("uses custom alias as short code when alias is available")
    void usesAlias_whenAvailable() {
        when(urlRepository.existsByShortCode("my-alias")).thenReturn(false);
        Url saved = urlWith("my-alias", "https://example.com", "my-alias", null);
        when(urlRepository.save(any())).thenReturn(saved);

        ShortenResponse response = service.shorten(
            ShortenRequest.builder()
                .longUrl("https://example.com")
                .customAlias("my-alias")
                .build()
        );

        assertThat(response.getShortCode()).isEqualTo("my-alias");
        assertThat(response.getShortUrl()).isEqualTo("http://localhost:8080/my-alias");
        verify(shortCodeGenerator, never()).nextCode();
    }

    @Test
    @DisplayName("throws AliasAlreadyExistsException when alias is taken")
    void throwsConflict_whenAliasAlreadyExists() {
        when(urlRepository.existsByShortCode("taken-alias")).thenReturn(true);

        assertThatThrownBy(() -> service.shorten(
            ShortenRequest.builder()
                .longUrl("https://example.com")
                .customAlias("taken-alias")
                .build()
        ))
            .isInstanceOf(AliasAlreadyExistsException.class)
            .hasMessageContaining("taken-alias");

        verify(urlRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // TTL computation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("computeCacheTtl returns default TTL when no expiration")
    void ttl_noExpiration() {
        long defaultTtl = appProperties.getCache().getDefaultTtlSeconds();
        assertThat(service.computeCacheTtl(null).getSeconds()).isEqualTo(defaultTtl);
    }

    @Test
    @DisplayName("computeCacheTtl returns remaining seconds when expiration is in the future")
    void ttl_futureExpiration() {
        LocalDateTime future = LocalDateTime.now().plusHours(2);
        long ttl = service.computeCacheTtl(future).getSeconds();
        // Should be approximately 7200s (2 hours), but bounded by default
        assertThat(ttl).isGreaterThan(7100).isLessThanOrEqualTo(7201);
    }

    @Test
    @DisplayName("computeCacheTtl returns 1 second for already-expired URLs")
    void ttl_alreadyExpired() {
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        assertThat(service.computeCacheTtl(past).getSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("computeCacheTtl caps at defaultTtl even for far-future expirations")
    void ttl_cappedAtDefault() {
        LocalDateTime farFuture = LocalDateTime.now().plusDays(365);
        long defaultTtl = appProperties.getCache().getDefaultTtlSeconds();
        assertThat(service.computeCacheTtl(farFuture).getSeconds()).isEqualTo(defaultTtl);
    }

    // -------------------------------------------------------------------------
    // Base URL edge cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("does not double-slash when base URL ends with /")
    void noDoubleSlash() {
        appProperties.setBaseUrl("http://localhost:8080/");
        service = new UrlShorteningService(urlRepository, shortCodeGenerator, urlCacheService, appProperties);

        when(shortCodeGenerator.nextCode()).thenReturn("test1234");
        Url saved = urlWith("test1234", "https://example.com", null, null);
        when(urlRepository.save(any())).thenReturn(saved);

        ShortenResponse response = service.shorten(
            ShortenRequest.builder().longUrl("https://example.com").build()
        );

        assertThat(response.getShortUrl()).isEqualTo("http://localhost:8080/test1234");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Url urlWith(String shortCode, String longUrl, String alias, LocalDateTime expiry) {
        Url url = new Url();
        url.setId(1L);
        url.setShortCode(shortCode);
        url.setLongUrl(longUrl);
        url.setCustomAlias(alias);
        url.setExpirationDate(expiry);
        url.setCreatedAt(LocalDateTime.now());
        return url;
    }
}
