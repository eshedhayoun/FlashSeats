-- ============================================================================
-- FlashSeats — the sale the cluster load-tests against.
--
-- WHY THIS FILE EXISTS. CatalogDevSeeder is @Profile("dev"), and the cluster
-- runs the `docker` profile, so `--profile cluster` comes up with an empty
-- catalog and the k6 harness has nothing to buy. There is no create-event
-- endpoint — AdminCatalogController serves only pre-warm — so the sale is
-- seeded here, in SQL, rather than by widening a profile that ADR-039 has just
-- finished narrowing.
--
-- WHY ID 9001 AND NOT 1. The postgres volume outlives any single run, and a
-- developer who has ever run `spring-boot:run` has CatalogDevSeeder's events 1
-- and 2 sitting in it. An earlier draft seeded id 1 with ON CONFLICT DO
-- NOTHING, which silently did nothing and pointed the whole load test at the
-- dev seeder's 700-seat sale while asserting against a capacity of 500. A
-- reserved id makes the load-test sale unmistakably ours, and safe to delete.
--
-- THE WINDOW IS UPCOMING ON PURPOSE. Pre-warm refuses anything but an UPCOMING
-- sale (ADR-004): seeding counters into a sale that is already open would write
-- total_capacity over a number that may already have sold, which is the exact
-- failure "never reseed from total_capacity while a sale is open" names. So the
-- window opens two minutes out, seed.sh pre-warms inside that gap, and the sale
-- opens with counters already vouched for.
--
-- Applied by docker/seed/seed.sh, which resets, pre-warms, and waits for OPEN.
-- ============================================================================

\set event_id 9001
\set tier_id  9001

-- --- reset ------------------------------------------------------------------
-- Scoped to this event alone. A load run has to be repeatable: the second run
-- against a drained counter sells nothing and proves nothing, so re-seeding
-- clears what the previous run wrote rather than layering on top of it.
--
-- Only ticket_tiers -> events is a real foreign key; orders and ticket_holds
-- carry event_id as a plain column because each module owns its own tables. So
-- the order here is deliberate rather than enforced.
DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE event_id = :event_id);
DELETE FROM orders       WHERE event_id = :event_id;
DELETE FROM ticket_holds WHERE event_id = :event_id;
DELETE FROM ticket_tiers WHERE event_id = :event_id;
DELETE FROM events       WHERE id       = :event_id;

-- --- seed -------------------------------------------------------------------
INSERT INTO events (
    id, title, description, venue_name,
    event_start_time, sale_start_time, sale_end_time, status
) VALUES (
    :event_id,
    'FlashSeats Load Test',
    'The 10,000-buyer flash sale. 500 seats, one tier, no second chances.',
    'Compose Arena',
    now() + interval '30 days',
    now() + interval '2 minutes',   -- UPCOMING, so pre-warm is legal (ADR-004)
    now() + interval '2 hours',     -- outlasts the run and the replica/Redis drills
    'PUBLISHED'                     -- findOpenEventIds() matches PUBLISHED only
);

INSERT INTO ticket_tiers (
    id, event_id, tier_name, price_cents, currency, total_capacity, max_per_order
) VALUES (
    :tier_id, :event_id, 'General Admission', 5000, 'USD',
    500,   -- must match K6_CAPACITY; the no-oversell threshold compares to it
    6      -- flashseats.hold.max-quantity is also 6, so the tier is not the binding limit
);

-- Both ids are BIGSERIAL and were supplied explicitly, which does NOT advance
-- their sequences. Left alone, the next event inserted by any other path draws
-- an id that is already taken. Bump them past what we just wrote.
SELECT setval('events_id_seq',       (SELECT MAX(id) FROM events),       true);
SELECT setval('ticket_tiers_id_seq', (SELECT MAX(id) FROM ticket_tiers), true);
