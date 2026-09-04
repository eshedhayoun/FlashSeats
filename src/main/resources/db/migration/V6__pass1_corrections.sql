-- ============================================================================
-- Pass 1 review corrections
--
-- `ticket_holds.quantity` carried CHECK (quantity > 0 AND quantity <= 6), but 6
-- is not a constant: the real ceiling is
--
--     min(flashseats.hold.max-quantity, ticket_tiers.max_per_order)
--
-- and both of those are configuration. Hardcoding one of the two inputs in the
-- schema meant raising the property produced a constraint violation rather than
-- a larger hold — and HoldService mapped every violation on this table to
-- HOLD_LIMIT_EXCEEDED, so the buyer was told they already held seats for an
-- event they had never touched.
--
-- The positive check stays: a hold for zero or negative seats is nonsense in
-- any configuration, which is exactly what belongs in a CHECK. The ceiling is
-- enforced in HoldService.createHold, which is the only place that knows both
-- halves of it.
-- ============================================================================

ALTER TABLE ticket_holds DROP CONSTRAINT ticket_holds_quantity_check;

ALTER TABLE ticket_holds
    ADD CONSTRAINT ticket_holds_quantity_check CHECK (quantity > 0);
