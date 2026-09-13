-- ============================================================================
-- bot — IP rules and the audit trail
--
-- NOT V6. Earlier documents called for `V6__bot.sql`, but V6 has been
-- V6__pass1_corrections.sql in every database that has ever run this schema,
-- and a migration is immutable the moment any database has applied it — Flyway
-- checksums the whole file, comments included. Renumbering it would refuse to
-- start every container with a "checksum mismatch for version 6".
--
-- This module wrote no tables at all until now, and both of these exist to
-- close §10 S5: session identity is free to mint, so the per-session bucket
-- does not constrain a determined attacker and the IP bucket — deliberately
-- loose at 300 burst, so NAT populations are not blocked — has been the only
-- real backstop (ADR-011, ADR-055).
-- ============================================================================

-- The manual override. Small by design: this is an operator's list, consulted
-- on EVERY request through an in-memory cache, never read per request from
-- here. A table on the hot path would put the rate limiter in the connection
-- pool it exists to protect (ADR-051).
--
-- `expires_at NULL` means permanent. A temporary block is the common case —
-- most abuse is a burst from one address during one sale — and a rule that
-- cannot expire is one somebody has to remember to remove.
CREATE TABLE ip_rules (
    id          BIGSERIAL    PRIMARY KEY,
    ip_address  VARCHAR(45)  NOT NULL UNIQUE,   -- 45 = INET6_ADDRSTRLEN
    action      VARCHAR(16)  NOT NULL,          -- ALLOW | DENY
    reason      VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ
);

-- Non-ALLOWED outcomes ONLY. A row per allowed request would be a write per
-- request during a flash sale — the one workload this system is built around —
-- and the table would be unreadable for the very purpose it exists to serve.
-- What an operator needs is the exceptions, and there is no useful question
-- about the normal path that this table would answer better than the metrics.
CREATE TABLE bot_audit_logs (
    id          BIGSERIAL    PRIMARY KEY,
    session_id  VARCHAR(255),
    ip_address  VARCHAR(45),
    path        VARCHAR(255),
    outcome     VARCHAR(32)  NOT NULL,          -- RATE_LIMITED | IP_BLOCKED | VERIFICATION_FAILED | VERIFICATION_DEGRADED
    detail      VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Read newest-first, always: the question is "what is happening right now",
-- never "what happened in March".
CREATE INDEX idx_bot_audit_recent ON bot_audit_logs (created_at DESC);
