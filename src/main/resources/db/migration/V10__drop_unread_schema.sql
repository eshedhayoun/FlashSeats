-- ============================================================================
-- V10 — remove schema nothing reads.
--
-- Every object dropped here was verified unreferenced by grep over src/main and
-- src/test: no query filters on these columns, and no Java code writes or reads
-- the dropped column.
--
-- WHY IT IS WORTH A MIGRATION RATHER THAN A SHRUG. Two of these indexes sit on
-- `ticket_holds`, which takes an INSERT on *every reserve* — the hottest write
-- in the system, and the one the connection pool is already the ceiling for
-- (hikaricp_connections_pending peaked at 202 against a pool of 30 in the
-- five-sale drill). An index nobody queries is pure write amplification there.
--
-- A dead index is also a false signal about intent: a reader finds
-- `idx_orders_email` and reasonably concludes something looks orders up by
-- buyer email. Nothing does.
--
-- WHAT COMES BACK, AND WHEN. Three of these — idx_orders_intent, idx_pay_order
-- and idx_pay_hold — exist for the Stripe webhook, which is not built (the
-- module has no controller at all; docs/modules/payment.md says so). They are
-- correct indexes for a query that does not exist yet. Re-add them in the same
-- migration that adds the webhook, where their justification is visible.
--
-- WHAT IS DELIBERATELY KEPT. ticket_holds.settled_at and settle_reason are also
-- read by nothing in Java, and they stay: they are the forensic record of the
-- settle-once claim, which is the guarantee an incident is most likely to be
-- about. "Unread by code" is not "useless to a human with psql".
-- ============================================================================

-- outbox_events.last_error: declared in V3, mapped on the entity, and never
-- written by anything. OutboxRelay records failures by leaving a row PROCESSING
-- for the stale-claim sweep, not by annotating it.
ALTER TABLE outbox_events DROP COLUMN IF EXISTS last_error;

-- ticket_holds: hot write path.
--   idx_holds_session    — no query filters on user_session_id alone; the
--                          session lookup is served by the partial unique index
--                          idx_holds_one_active_per_session (V2).
--   idx_holds_event_tier — no query filters on (event_id, tier_id).
--                          sumActiveQuantityForTier filters on tier_id only,
--                          which is exactly why V9 had to add idx_holds_active_tier.
DROP INDEX IF EXISTS idx_holds_session;
DROP INDEX IF EXISTS idx_holds_event_tier;

-- orders: no query filters on user_email. stripe_payment_intent_id is written
-- but never queried — see "what comes back" above.
DROP INDEX IF EXISTS idx_orders_email;
DROP INDEX IF EXISTS idx_orders_intent;

-- payment_transactions: no query filters on either column. The only lookup on
-- this table is findByTransactionReference, served by its unique constraint.
DROP INDEX IF EXISTS idx_pay_order;
DROP INDEX IF EXISTS idx_pay_hold;
