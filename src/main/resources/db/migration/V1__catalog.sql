-- ============================================================================
-- catalog — events, tiers, and inventory ownership
--
-- `tier_inventory.remaining` is the live counter in the MVP, mutated by exactly
-- one statement (CatalogFacade.tryReserve). CHECK (remaining >= 0) is the
-- database-level guarantee that overbooking cannot be persisted even if every
-- line of Java above it were wrong.
--
-- Note: currency is VARCHAR(3) rather than CHAR(3) to avoid blank-padding on
-- read; the value is still an ISO-4217 code.
-- ============================================================================

CREATE TABLE events (
    id               BIGSERIAL PRIMARY KEY,
    title            VARCHAR(255) NOT NULL,
    description      TEXT,
    venue_name       VARCHAR(255) NOT NULL,
    event_start_time TIMESTAMPTZ  NOT NULL,
    sale_start_time  TIMESTAMPTZ  NOT NULL,
    sale_end_time    TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(32)  NOT NULL,          -- DRAFT | PUBLISHED | CANCELLED
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_events_sale_window CHECK (sale_end_time > sale_start_time)
);

CREATE TABLE ticket_tiers (
    id             BIGSERIAL PRIMARY KEY,
    event_id       BIGINT       NOT NULL REFERENCES events (id),
    tier_name      VARCHAR(100) NOT NULL,
    price_cents    BIGINT       NOT NULL CHECK (price_cents >= 0),
    currency       VARCHAR(3)   NOT NULL DEFAULT 'USD',
    total_capacity INT          NOT NULL CHECK (total_capacity > 0),
    max_per_order  INT          NOT NULL DEFAULT 6 CHECK (max_per_order > 0),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tiers_event ON ticket_tiers (event_id);

-- The live counter. One row per tier; the PK is the tier id so a reserve is a
-- single-row lookup and lock.
CREATE TABLE tier_inventory (
    tier_id    BIGINT PRIMARY KEY REFERENCES ticket_tiers (id),
    event_id   BIGINT      NOT NULL,
    remaining  INT         NOT NULL CHECK (remaining >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tier_inventory_event ON tier_inventory (event_id);
