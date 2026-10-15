-- ============================================================================
-- V12 — remove schema nothing reads.
--
-- Was V10 before the merge with the payment work, which took V10 and V11. A
-- version number is first-come, and two migrations sharing one is a Flyway
-- startup failure on every replica.
--
-- Every object dropped here was re-verified unreferenced AFTER that merge, not
-- before it: the Stripe gateway, the webhook and the IP-rule surface arrived
-- between this migration being written and being applied, and they changed the
-- answer for half of what it originally dropped.
--
-- WHY IT IS WORTH A MIGRATION RATHER THAN A SHRUG. Both surviving index drops
-- sit on `ticket_holds`, which takes an INSERT on *every reserve* — the hottest
-- write in the system, and the one the connection pool is already the ceiling
-- for (hikaricp_connections_pending peaked at 202 against a pool of 30 in the
-- five-sale drill). An index nobody queries is pure write amplification there.
--
-- A dead index is also a false signal about intent: a reader finds
-- `idx_orders_email` and reasonably concludes something looks orders up by
-- buyer email. Nothing does.
--
-- WHAT THIS MIGRATION NO LONGER DROPS, AND WHY THAT MATTERS MORE THAN WHAT IT
-- DOES. The first draft also dropped `idx_pay_hold`, `idx_pay_order` and
-- `idx_orders_intent`, on the reasoning that they were built for a Stripe
-- webhook that did not exist and "come back with it". The webhook arrived in the
-- same week:
--
--   * `idx_pay_hold` is now GENUINELY USED.
--     `findFirstByHoldTokenAndStatusOrderByIdDesc` is the 3-D Secure resume —
--     it finds the intent a buyer was sent away to authenticate so the retry
--     re-reads that charge instead of opening a second one. Dropping it would
--     have put a sequential scan on the authentication path.
--   * `idx_pay_order` and `idx_orders_intent` are still queried by nothing, and
--     are LEFT IN PLACE anyway. `payment_transactions` and the intent column are
--     under active construction; shaving writes off a table someone is still
--     extending, on a path that is not hot, is not worth the coordination cost.
--     Revisit when that module settles.
--
-- The general lesson, recorded because it cost a rewrite: "unreferenced today"
-- is a safe reason to delete code, and a weaker reason to delete an index whose
-- feature is merely *not built yet*. Prefer dropping indexes on tables nothing
-- is actively building on.
--
-- WHAT IS DELIBERATELY KEPT. `ticket_holds.settled_at` and `settle_reason` are
-- read by nothing in Java, and they stay: they are the forensic record of the
-- settle-once claim, which is the guarantee an incident is most likely to be
-- about. "Unread by code" is not "useless to a human with psql".
-- ============================================================================

-- outbox_events.last_error: declared in V3, mapped on the entity, and never
-- written by anything. OutboxRelay records failures by leaving a row PROCESSING
-- for the stale-claim sweep, not by annotating it.
ALTER TABLE outbox_events DROP COLUMN IF EXISTS last_error;

-- ticket_holds: hot write path, and untouched by the payment work.
--   idx_holds_session    — no query filters on user_session_id alone; the
--                          session lookup is served by the partial unique index
--                          idx_holds_one_active_per_session (V2).
--   idx_holds_event_tier — no query filters on (event_id, tier_id).
--                          sumActiveQuantityForTier filters on tier_id only,
--                          which is exactly why V9 had to add idx_holds_active_tier.
DROP INDEX IF EXISTS idx_holds_session;
DROP INDEX IF EXISTS idx_holds_event_tier;

-- orders: no query filters on user_email, and none was added by the webhook —
-- it correlates through payment_transactions.stripe_payment_intent_id, which is
-- UNIQUE in V4 and needs no separate index.
DROP INDEX IF EXISTS idx_orders_email;
