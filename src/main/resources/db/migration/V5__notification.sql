-- ============================================================================
-- notification — delivery log
--
-- UNIQUE (order_number, kind) IS the idempotency guarantee (ADR-015). The
-- consumer inserts this row BEFORE rendering or sending, and lets the unique
-- violation stop a duplicate. A preceding SELECT would be a race that two
-- workers both pass — which is how a buyer receives two tickets.
--
-- `kind` is what allows the same order to receive both a ticket and, on the
-- refund path, a separate notice.
-- ============================================================================

CREATE TABLE notification_logs (
    id              BIGSERIAL PRIMARY KEY,
    order_number    VARCHAR(64)  NOT NULL,
    kind            VARCHAR(32)  NOT NULL,   -- TICKET_DELIVERY | REFUND_NOTICE
    recipient_email VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL,   -- PENDING | SENT | FAILED | DLQ
    retry_count     INT          NOT NULL DEFAULT 0,
    failure_reason  VARCHAR(500),
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification UNIQUE (order_number, kind)
);
