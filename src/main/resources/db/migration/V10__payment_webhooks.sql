-- ============================================================================
-- payment — webhook replay protection
--
-- The provider redelivers on any non-2xx, and it does so more patiently than we
-- should. That is a feature: it is what makes a charge whose HTTP response was
-- lost still reach an order. It also means the SAME event arrives more than
-- once, across three replicas, and settling an order twice is not idempotent —
-- it would consume a hold that is already consumed and, on the ADR-012 arm,
-- refund a charge that was already refunded.
--
-- So this table is a CLAIM, not a log. `stripe_event_id` as the primary key is
-- the whole mechanism: the insert is `ON CONFLICT DO NOTHING` and the ROWCOUNT
-- is the answer, exactly as `notification_logs` does it (ADR-038). Never an
-- insert whose exception is caught — a flush that violates a constraint marks
-- the transaction rollback-only, so the catch block's `return` throws
-- UnexpectedRollbackException at commit instead of returning.
--
-- And, per ADR-038's other half: a claim is RELEASED when the work it guarded
-- did not happen. If settlement throws, the row is deleted and a non-2xx goes
-- back, so the redelivery finds a clean claim. A claim that outlived its work
-- would mean the provider stops retrying a charge that never reached an order.
--
-- `processed_at IS NULL` therefore means "in flight", never "failed": a failure
-- removes the row. Rows are kept after success because they are the only record
-- of which deliveries were duplicates.
-- ============================================================================

CREATE TABLE webhook_events (
    stripe_event_id   VARCHAR(64)  PRIMARY KEY,
    event_type        VARCHAR(64)  NOT NULL,
    payment_intent_id VARCHAR(255),
    hold_token        VARCHAR(64),
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ
);

-- Support answers "did anything ever arrive for this reservation?", which is
-- the first question asked when a buyer says they were charged and got nothing.
CREATE INDEX idx_webhook_events_hold ON webhook_events (hold_token);
