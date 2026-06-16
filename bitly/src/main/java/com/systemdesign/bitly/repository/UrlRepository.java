package com.systemdesign.bitly.repository;

import com.systemdesign.bitly.model.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Url} entities.
 *
 * <p>The primary lookup method is {@link #findByShortCode(String)}, which maps to a B-tree
 * index scan on the {@code short_code} column.  All other methods are secondary utilities.
 *
 * <p>Write throughput note: at 1000:1 read/write ratio with 100M DAU the write QPS is on the
 * order of ~1,200/s (assuming ~1.2B daily operations → 120M writes/day → ~1,400 writes/s at
 * peak).  PostgreSQL with a single primary comfortably handles this; read replicas handle the
 * ~1.2M read QPS with Redis absorbing the hot-code tier.
 */
@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {

    /**
     * Look up a URL record by its short code.  This is the hot path for redirects; the
     * B-tree index on {@code short_code} makes this a single index-range scan.
     */
    Optional<Url> findByShortCode(String shortCode);

    /**
     * Existence check used when validating custom aliases.  More efficient than
     * {@code findByShortCode(...).isPresent()} because it projects only a boolean.
     */
    boolean existsByShortCode(String shortCode);

    /**
     * Bulk-delete records whose expiration date is before {@code cutoff}.
     * Intended for a scheduled cleanup job (not wired in this service instance,
     * but the query is here for operational tooling).
     */
    @Query("DELETE FROM Url u WHERE u.expirationDate IS NOT NULL AND u.expirationDate < :cutoff")
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Count URLs created after a given instant — useful for capacity monitoring dashboards.
     */
    @Query("SELECT COUNT(u) FROM Url u WHERE u.createdAt >= :since")
    long countCreatedSince(@Param("since") LocalDateTime since);
}
