-- =============================================================================
-- Bitly URL Shortener — Database Schema
-- =============================================================================
-- Run mode: executed by Spring on startup (spring.sql.init.mode=always).
-- All DDL statements use IF NOT EXISTS so they are idempotent on re-deployment.
--
-- Database: PostgreSQL 15+
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Table: urls
-- ---------------------------------------------------------------------------
-- Stores one record per shortened URL.
--
-- Column notes:
--   short_code      Business key; 8-char base62 for generated codes, up to 100
--                   chars for custom aliases. UNIQUE enforced here AND at the
--                   application layer to surface alias conflicts with HTTP 409
--                   before hitting the DB unique constraint.
--
--   long_url        TEXT (no length limit) to accommodate arbitrarily long URLs.
--                   URLs up to ~8000 chars are common in OAuth redirect_uri flows.
--
--   custom_alias    Denormalised copy of the alias (subset of short_code when set).
--                   Kept for audit / analytics queries; NULL for generated codes.
--
--   expiration_date When not NULL, the redirect service returns 410 Gone after
--                   this timestamp.
--
--   created_at      Set by the JPA @PrePersist hook; also defaults to NOW() in
--                   case of direct DB inserts (migrations, seed scripts).
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS urls (
    id              BIGSERIAL       PRIMARY KEY,
    short_code      VARCHAR(100)    NOT NULL,
    long_url        TEXT            NOT NULL,
    custom_alias    VARCHAR(100),
    expiration_date TIMESTAMP,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_urls_short_code UNIQUE (short_code)
);

-- ---------------------------------------------------------------------------
-- Index: idx_urls_short_code
-- ---------------------------------------------------------------------------
-- B-tree index on short_code — the primary lookup key for every redirect.
-- The UNIQUE constraint above already creates an implicit unique index;
-- the explicit CREATE INDEX is kept here for documentation and to make the
-- index name deterministic (matches the JPA @Index annotation name).
-- If the index already exists (due to the UNIQUE constraint), this is a no-op.
-- ---------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_urls_short_code ON urls (short_code);

-- ---------------------------------------------------------------------------
-- Index: idx_urls_expiration_date
-- ---------------------------------------------------------------------------
-- Partial index covering only rows with a non-null expiration_date.  Used by
-- the scheduled cleanup job (deleteExpiredBefore) and by monitoring queries
-- that count soon-to-expire links.
-- ---------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_urls_expiration_date
    ON urls (expiration_date)
    WHERE expiration_date IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Index: idx_urls_created_at
-- ---------------------------------------------------------------------------
-- Supports time-series monitoring queries (countCreatedSince) and range scans
-- by the ops team (e.g. "how many URLs were created in the last hour?").
-- ---------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_urls_created_at ON urls (created_at DESC);
