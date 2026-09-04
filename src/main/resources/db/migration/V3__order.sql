-- ============================================================================
-- order — the ACID ledger and the transactional outbox
--
-- UNIQUE (hold_token) is the strongest overbooking guard in the system: one
-- hold can never become two orders, however many times a client submits
-- (ADR-002). It is also what the find-or-create retry semantics key off.
-- ============================================================================

CREATE SEQUENCE order_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE orders (
    id                       BIGSERIAL PRIMARY KEY,
    order_number             VARCHAR(64)  NOT NULL UNIQUE,
    hold_token               VARCHAR(64)  NOT NULL UNIQUE,   -- ADR-002
    user_session_id          VARCHAR(255) NOT NULL,
    user_email               VARCHAR(255) NOT NULL,
    receipt_token            VARCHAR(255) NOT NULL,          -- signed capability (ADR-010)
    event_id                 BIGINT       NOT NULL,
    total_amount_cents       BIGINT       NOT NULL CHECK (total_amount_cents >= 0),
    currency                 VARCHAR(3)   NOT NULL DEFAULT 'USD',
    status                   VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    payment_transaction_ref  VARCHAR(64),
    stripe_payment_intent_id VARCHAR(255),
    payment_attempts         INT          NOT NULL DEFAULT 0,
    failure_reason           VARCHAR(255),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_session ON orders (user_session_id);
CREATE INDEX idx_orders_email   ON orders (user_email);
CREATE INDEX idx_orders_intent  ON orders (stripe_payment_intent_id);

CREATE TABLE order_items (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    event_id         BIGINT       NOT NULL,
    tier_id          BIGINT       NOT NULL,
    tier_name        VARCHAR(100) NOT NULL,   -- snapshot: tier names may change later
    quantity         INT          NOT NULL CHECK (quantity > 0),
    unit_price_cents BIGINT       NOT NULL CHECK (unit_price_cents >= 0),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_items_order ON order_items (order_id);
CREATE INDEX idx_order_items_tier  ON order_items (tier_id);

-- The outbox row is written inside the same transaction as the order, so a
-- confirmed order without queued fulfilment is impossible. The relay claims
-- rows with FOR UPDATE SKIP LOCKED and publishes OUTSIDE any transaction
-- (ADR-023); `claimed_at` is how a crash between claim and publish is found
-- and re-swept.
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    event_type     VARCHAR(64) NOT NULL,      -- ORDER_CONFIRMED | ORDER_REFUNDED
    payload        JSONB       NOT NULL,
    status         VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count    INT         NOT NULL DEFAULT 0,
    last_error     VARCHAR(500),
    claimed_at     TIMESTAMPTZ,
    processed_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_pending    ON outbox_events (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_processing ON outbox_events (claimed_at) WHERE status = 'PROCESSING';
