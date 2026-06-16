-- Dropbox System Design Reference Implementation
-- Schema for PostgreSQL
-- Run once at startup (spring.sql.init.mode=always with ddl-auto=validate)

CREATE TABLE IF NOT EXISTS file_metadata (
    id               BIGSERIAL    PRIMARY KEY,
    file_id          VARCHAR(36)  NOT NULL UNIQUE,
    file_name        VARCHAR(500) NOT NULL,
    file_size_bytes  BIGINT       NOT NULL,
    mime_type        VARCHAR(200),
    owner_id         VARCHAR(200) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    s3_key           VARCHAR(500) NOT NULL,
    s3_upload_id     VARCHAR(200),
    total_chunks     INTEGER      NOT NULL DEFAULT 1,
    checksum         VARCHAR(64),
    compressed       BOOLEAN      NOT NULL DEFAULT false,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_file_metadata_owner_id
    ON file_metadata(owner_id);

CREATE INDEX IF NOT EXISTS idx_file_metadata_updated_at
    ON file_metadata(updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_file_metadata_owner_status
    ON file_metadata(owner_id, status);

-- --------------------------------------------------------

CREATE TABLE IF NOT EXISTS file_chunks (
    id               BIGSERIAL    PRIMARY KEY,
    file_id          VARCHAR(36)  NOT NULL,
    chunk_number     INTEGER      NOT NULL,
    etag             VARCHAR(200),
    chunk_size_bytes BIGINT,
    checksum         VARCHAR(64),
    uploaded         BOOLEAN      NOT NULL DEFAULT false,
    UNIQUE (file_id, chunk_number)
);

CREATE INDEX IF NOT EXISTS idx_file_chunks_file_id
    ON file_chunks(file_id);

-- --------------------------------------------------------

CREATE TABLE IF NOT EXISTS shared_files (
    file_id              VARCHAR(36)  NOT NULL,
    shared_with_user_id  VARCHAR(200) NOT NULL,
    shared_by_user_id    VARCHAR(200) NOT NULL,
    permission           VARCHAR(10)  NOT NULL DEFAULT 'READ',
    shared_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (file_id, shared_with_user_id)
);

CREATE INDEX IF NOT EXISTS idx_shared_files_user_id
    ON shared_files(shared_with_user_id);

CREATE INDEX IF NOT EXISTS idx_shared_files_file_id
    ON shared_files(file_id);
