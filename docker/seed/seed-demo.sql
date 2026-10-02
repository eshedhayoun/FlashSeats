-- FlashSeats evaluator catalog: the two user-facing demo sales.
-- The windows remain UPCOMING until the launcher pre-warms their counters.

\set aurora_id 9101
\set aurora_vip_tier_id 9101
\set aurora_floor_tier_id 9102
\set aurora_general_tier_id 9103
\set midnight_id 9102
\set midnight_tier_id 9104

DELETE FROM order_items WHERE order_id IN (
    SELECT id FROM orders WHERE event_id IN (:aurora_id, :midnight_id)
);
DELETE FROM orders WHERE event_id IN (:aurora_id, :midnight_id);
DELETE FROM ticket_holds WHERE event_id IN (:aurora_id, :midnight_id);
DELETE FROM ticket_tiers WHERE event_id IN (:aurora_id, :midnight_id);
DELETE FROM events WHERE id IN (:aurora_id, :midnight_id);

INSERT INTO events (
    id, title, description, venue_name,
    event_start_time, sale_start_time, sale_end_time, status
) VALUES
(
    :aurora_id,
    'Aurora Fest 2026',
    'Three stages, one night, under the northern lights.',
    'Riverside Arena',
    now() + interval '30 days',
    now() + interval '2 minutes',
    now() + interval '8 hours',
    'PUBLISHED'
),
(
    :midnight_id,
    'Midnight Sessions',
    'An intimate late set, on sale now.',
    'The Vault',
    now() + interval '30 days',
    now() + interval '2 minutes',
    now() + interval '8 hours',
    'PUBLISHED'
);

INSERT INTO ticket_tiers (
    id, event_id, tier_name, price_cents, currency, total_capacity, max_per_order
) VALUES
(:aurora_vip_tier_id, :aurora_id, 'VIP', 7500, 'USD', 50, 6),
(:aurora_floor_tier_id, :aurora_id, 'Floor', 4500, 'USD', 150, 6),
(:aurora_general_tier_id, :aurora_id, 'General Admission', 2500, 'USD', 500, 6),
(:midnight_tier_id, :midnight_id, 'General Admission', 3000, 'USD', 200, 4);

SELECT setval('events_id_seq', GREATEST((SELECT MAX(id) FROM events), :midnight_id), true);
SELECT setval(
    'ticket_tiers_id_seq',
    GREATEST((SELECT MAX(id) FROM ticket_tiers), :midnight_tier_id, :aurora_general_tier_id),
    true
);
