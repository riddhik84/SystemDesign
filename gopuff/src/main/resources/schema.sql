-- GoPuff-like local delivery service schema.
-- Designed for PostgreSQL. Loaded by Spring's SQL init (spring.sql.init.mode=always).

CREATE TABLE IF NOT EXISTS items (
    id BIGSERIAL PRIMARY KEY,
    item_id VARCHAR(36) UNIQUE NOT NULL,
    name VARCHAR(500) NOT NULL,
    category VARCHAR(100),
    description TEXT,
    price NUMERIC(10,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS distribution_centers (
    id BIGSERIAL PRIMARY KEY,
    dc_id VARCHAR(36) UNIQUE NOT NULL,
    name VARCHAR(500) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    region_code VARCHAR(10),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_dc_region_code ON distribution_centers(region_code);
CREATE INDEX IF NOT EXISTS idx_dc_active ON distribution_centers(active);

CREATE TABLE IF NOT EXISTS inventory (
    id BIGSERIAL PRIMARY KEY,
    item_id VARCHAR(36) NOT NULL,
    dc_id VARCHAR(36) NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 0,
    reserved_quantity INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(item_id, dc_id),
    CONSTRAINT chk_quantity CHECK (quantity >= 0),
    CONSTRAINT chk_reserved CHECK (reserved_quantity >= 0),
    CONSTRAINT chk_reserved_lte_quantity CHECK (reserved_quantity <= quantity)
);
CREATE INDEX IF NOT EXISTS idx_inventory_dc_id ON inventory(dc_id);
CREATE INDEX IF NOT EXISTS idx_inventory_item_id ON inventory(item_id);

CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(36) UNIQUE NOT NULL,
    user_id VARCHAR(200) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    fulfilling_dc_id VARCHAR(36),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);

CREATE TABLE IF NOT EXISTS order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    item_id VARCHAR(36) NOT NULL,
    quantity INTEGER NOT NULL,
    price_at_order NUMERIC(10,2) NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);
