-- ============================================================================
-- hold — time-bound reservations
--
-- `ticket_holds` is the AUTHORITY for a reservation's lifecycle (ADR-019).
-- Every terminal transition is one conditional UPDATE:
--
--   UPDATE ticket_holds SET status = ?, settled_at = now(), settle_reason = ?
--    WHERE hold_token = ? AND status = 'ACTIVE';   -- rowcount 1 => you won
--
-- Stock is restored only by the caller that gets rowcount = 1. Everyone else
-- gets 0 and does nothing, which is what makes consume / release / expire /
-- sweep safe against each other and across replicas with no distributed lock.
-- ============================================================================

CREATE TABLE ticket_holds (
    id              BIGSERIAL PRIMARY KEY,
    hold_token      VARCHAR(64)  NOT NULL UNIQUE,
    user_session_id VARCHAR(255) NOT NULL,
    event_id        BIGINT       NOT NULL,
    tier_id         BIGINT       NOT NULL,
    quantity        INT          NOT NULL CHECK (quantity > 0 AND quantity <= 6),
    status          VARCHAR(32)  NOT NULL,          -- ACTIVE | CONSUMED | RELEASED | EXPIRED
    expires_at      TIMESTAMPTZ  NOT NULL,
    extended_count  INT          NOT NULL DEFAULT 0,   -- enforces the one-extension ceiling
    settled_at      TIMESTAMPTZ,                       -- when it left ACTIVE
    settle_reason   VARCHAR(64),                       -- CONSUMED | USER_CANCEL | TTL | SWEEPER
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_holds_session    ON ticket_holds (user_session_id);
CREATE INDEX idx_holds_event_tier ON ticket_holds (event_id, tier_id);

-- Drives the reconciliation sweeper: only ACTIVE rows can expire.
CREATE INDEX idx_holds_sweeper ON ticket_holds (expires_at) WHERE status = 'ACTIVE';

-- ADR-017: at most one live hold per session per event. A partial unique index
-- makes this a database guarantee rather than a check-then-insert that races.
CREATE UNIQUE INDEX idx_holds_one_active_per_session
    ON ticket_holds (user_session_id, event_id) WHERE status = 'ACTIVE';
