-- ============================================================================
-- payment — the gateway transaction ledger
--
-- Idempotency is anchored to the HOLD, not to a client-chosen string (ADR-014):
-- a client that regenerates its key on retry cannot bypass UNIQUE (hold_token)
-- on `orders`. This table is the durable record of what the gateway was asked
-- and what it answered.
-- ============================================================================

CREATE TABLE payment_transactions (
    id                       BIGSERIAL PRIMARY KEY,
    transaction_reference    VARCHAR(64)  NOT NULL UNIQUE,
    order_number             VARCHAR(64)  NOT NULL,
    hold_token               VARCHAR(64)  NOT NULL,
    user_session_id          VARCHAR(255) NOT NULL,
    stripe_payment_intent_id VARCHAR(255) UNIQUE,
    client_idempotency_key   VARCHAR(64),
    amount_cents             BIGINT       NOT NULL CHECK (amount_cents >= 0),
    currency                 VARCHAR(3)   NOT NULL DEFAULT 'USD',
    status                   VARCHAR(32)  NOT NULL,   -- INITIATED | PROCESSING | SUCCEEDED | FAILED | REFUNDED
    failure_code             VARCHAR(64),
    failure_reason           VARCHAR(255),
    attempt_number           INT          NOT NULL DEFAULT 1,
    refunded_amount_cents    BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_pay_order ON payment_transactions (order_number);
CREATE INDEX idx_pay_hold  ON payment_transactions (hold_token);
