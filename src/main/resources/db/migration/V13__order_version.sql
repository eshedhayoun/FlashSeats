-- ============================================================================
-- V13 — orders get an optimistic-lock version (ADR-064).
--
-- The checkout and the payment webhook can settle the SAME charge at the same
-- moment, and every order transition was load, modify, flush. Hibernate writes
-- every column, so whichever transaction flushed second wrote its stale copy over
-- the first: a CONFIRMED purchase could be written back to PENDING and then
-- abandoned, or refunded after the buyer had their ticket.
--
-- With a version column every entity write is a compare-and-set, and the
-- conditional transitions in OrderRepository bump it too, so an entity loaded
-- before one of them fails its own flush instead of overwriting it.
--
-- DEFAULT 0 backfills existing rows; nothing reads the value except Hibernate.
-- ============================================================================

ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
