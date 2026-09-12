-- ============================================================================
-- FlashSeats — five sales at once, for the ADR-049 drill.
--
-- WHY THIS EXISTS. Every instrument in this repo runs ONE event, and every
-- capacity number in the design was derived for one event. ADR-028 caps the
-- promotion batch at `hikariMax x 1.5` and that is sound for a single sale —
-- but PromotionWorker loops every open event and applies the cap PER EVENT,
-- and `queue:promote:{e}` is per event too, so replicas promote different sales
-- in the same second against one shared connection pool:
--
--     R replicas x E events x batchSize  =  3 x 5 x 45  =  675 admissions/sec
--     against R x 30                     =              90 connections
--
-- Nothing errors when that happens. Under virtual threads the requests queue on
-- HikariCP and p99 collapses, which is exactly the failure ADR-028 exists to
-- prevent, arriving through the door it left open. The 2,000-VU single-sale run
-- could not see it, and the drift gauge will not either — it is a LATENCY
-- failure, not a correctness one, so `hikaricp_connections_pending` is the
-- number that matters.
--
-- Ids 9001..9005, extending seed.sql's reserved range for the same reason it
-- reserved one: the postgres volume outlives a run, and CatalogDevSeeder's
-- events 1 and 2 are sitting in it.
--
-- Applied by docker/seed/seed-concurrent.sh, which resets, pre-warms all five,
-- and waits for every one of them to open.
-- ============================================================================

\set events    5
\set capacity  500
\set first_id  9001

-- --- reset ------------------------------------------------------------------
-- Scoped to the reserved range, never a truncate. A load run has to be
-- repeatable: a second run against drained counters sells nothing and proves
-- nothing.
--
-- Note this also clears event 9001, which seed.sql owns. The two seeders share
-- the id and deliberately cannot both be live — running one after the other is
-- how you switch drills, and leaving a half-seeded 9001 behind would give the
-- single-sale harness a sale with the wrong capacity.
DELETE FROM order_items
 WHERE order_id IN (SELECT id FROM orders
                     WHERE event_id BETWEEN :first_id AND :first_id + :events - 1);
DELETE FROM orders       WHERE event_id BETWEEN :first_id AND :first_id + :events - 1;
DELETE FROM ticket_holds WHERE event_id BETWEEN :first_id AND :first_id + :events - 1;
DELETE FROM ticket_tiers WHERE event_id BETWEEN :first_id AND :first_id + :events - 1;
DELETE FROM events       WHERE id       BETWEEN :first_id AND :first_id + :events - 1;

-- --- seed -------------------------------------------------------------------
-- All five open at the SAME instant. Staggering them would be the kinder test
-- and the wrong one: the risk is E sales promoting concurrently, so they have
-- to contend for the pool at the same moment.
INSERT INTO events (
    id, title, description, venue_name,
    event_start_time, sale_start_time, sale_end_time, status
)
SELECT
    :first_id + n,
    'Concurrent Sale ' || (n + 1),
    'One of ' || :events || ' sales opening at the same instant (ADR-049 drill).',
    'Compose Arena ' || (n + 1),
    now() + interval '30 days',
    now() + interval '2 minutes',   -- UPCOMING, so pre-warm is legal (ADR-004)
    now() + interval '2 hours',
    'PUBLISHED'
FROM generate_series(0, :events - 1) AS n;

INSERT INTO ticket_tiers (
    id, event_id, tier_name, price_cents, currency, total_capacity, max_per_order
)
SELECT
    :first_id + n, :first_id + n, 'General Admission', 5000, 'USD',
    :capacity,
    6
FROM generate_series(0, :events - 1) AS n;

-- Explicit ids do not advance a BIGSERIAL's sequence. Left alone, the next
-- event inserted by any other path draws an id that is already taken.
SELECT setval('events_id_seq',       (SELECT MAX(id) FROM events),       true);
SELECT setval('ticket_tiers_id_seq', (SELECT MAX(id) FROM ticket_tiers), true);

-- Every column qualified: `events` and `ticket_tiers` both have an `id`, so a
-- bare `id` here is ambiguous and the join fails outright.
SELECT e.id AS event_id, e.title, t.id AS tier_id, t.total_capacity
  FROM events e JOIN ticket_tiers t ON t.event_id = e.id
 WHERE e.id BETWEEN :first_id AND :first_id + :events - 1
 ORDER BY e.id;
