package com.systemdesign.bitly.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entity representing a shortened URL record stored in PostgreSQL.
 *
 * <p>The {@code short_code} column carries a unique index (defined in schema.sql and mirrored
 * via the {@code @Table} annotation) so that point-lookups on the read path execute in O(log n)
 * with a B-tree index rather than a full table scan.
 *
 * <p>The {@code id} uses PostgreSQL BIGSERIAL (auto-increment) purely as the surrogate primary
 * key. The business key for all lookups is {@code short_code}.
 */
@Entity
@Table(
    name = "urls",
    indexes = {
        @Index(name = "idx_urls_short_code", columnList = "short_code", unique = true)
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Url {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The 8-character base62-encoded short code (e.g. "0a3Zk8mQ").
     * For custom aliases this may be up to 100 characters.
     */
    @Column(name = "short_code", length = 100, nullable = false, unique = true)
    private String shortCode;

    /** The original long URL supplied by the client. Stored as TEXT to handle arbitrarily long URLs. */
    @Column(name = "long_url", nullable = false, columnDefinition = "TEXT")
    private String longUrl;

    /**
     * Optional human-readable alias chosen by the user (e.g. "my-promo").
     * When present it is used as-is for {@code short_code}.
     */
    @Column(name = "custom_alias", length = 100)
    private String customAlias;

    /**
     * Optional wall-clock expiry. After this instant the redirect service treats the record
     * as non-existent and returns HTTP 410 Gone.
     */
    @Column(name = "expiration_date")
    private LocalDateTime expirationDate;

    /** Creation timestamp; set automatically in {@link #onCreate()}. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /** Convenience: returns true when an expiration is set and has already passed. */
    public boolean isExpired() {
        return expirationDate != null && LocalDateTime.now().isAfter(expirationDate);
    }
}
