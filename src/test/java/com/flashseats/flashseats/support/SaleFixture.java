package com.flashseats.flashseats.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds sale fixtures by writing rows directly.
 *
 * <p>Raw SQL rather than the modules' own repositories, deliberately: reaching into
 * {@code catalog.repository} from a test would create exactly the boundary violation
 * {@code ModularityTests} exists to prevent, and the check does not care that the caller is a test.
 *
 * <p>It also keeps fixtures honest. A test that seeds through the same repositories it is verifying
 * can pass because both share a bug.
 */
@Component
public class SaleFixture {

    private final JdbcTemplate jdbc;

    public SaleFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Wipes every table. Called between tests so each starts from a known, empty world. */
    public void reset() {
        jdbc.execute(
                """
                TRUNCATE notification_logs, payment_transactions, outbox_events, order_items,
                         orders, ticket_holds, tier_inventory, ticket_tiers, events
                RESTART IDENTITY CASCADE
                """);
    }

    /** An event whose sale is open right now. */
    public long openEvent(String title) {
        Instant now = Instant.now();
        return insertEvent(title, now.minus(1, ChronoUnit.MINUTES), now.plus(8, ChronoUnit.HOURS));
    }

    /** An event whose sale has not started, for pre-warm and countdown paths. */
    public long upcomingEvent(String title) {
        Instant now = Instant.now();
        return insertEvent(title, now.plus(1, ChronoUnit.HOURS), now.plus(8, ChronoUnit.HOURS));
    }

    /** An event whose sale window has already closed. */
    public long closedEvent(String title) {
        Instant now = Instant.now();
        return insertEvent(title, now.minus(4, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS));
    }

    /** A tier with inventory seeded, as pre-warm would have left it. */
    public long tier(long eventId, String name, long priceCents, int capacity) {
        Long tierId = jdbc.queryForObject(
                """
                INSERT INTO ticket_tiers
                       (event_id, tier_name, price_cents, currency, total_capacity, max_per_order,
                        created_at, updated_at)
                VALUES (?, ?, ?, 'USD', ?, 6, now(), now())
                RETURNING id
                """,
                Long.class,
                eventId, name, priceCents, capacity);

        jdbc.update(
                "INSERT INTO tier_inventory (tier_id, event_id, remaining, updated_at) VALUES (?, ?, ?, now())",
                tierId, eventId, capacity);
        return tierId;
    }

    /** A tier deliberately left with no counter, to exercise the missing-inventory fault path. */
    public long tierWithoutInventory(long eventId, String name, long priceCents, int capacity) {
        return jdbc.queryForObject(
                """
                INSERT INTO ticket_tiers
                       (event_id, tier_name, price_cents, currency, total_capacity, max_per_order,
                        created_at, updated_at)
                VALUES (?, ?, ?, 'USD', ?, 6, now(), now())
                RETURNING id
                """,
                Long.class,
                eventId, name, priceCents, capacity);
    }

    public int remaining(long tierId) {
        Integer remaining = jdbc.queryForObject(
                "SELECT remaining FROM tier_inventory WHERE tier_id = ?", Integer.class, tierId);
        return remaining == null ? -1 : remaining;
    }

    public String holdStatus(String holdToken) {
        return jdbc.queryForObject(
                "SELECT status FROM ticket_holds WHERE hold_token = ?", String.class, holdToken);
    }

    public int countHolds(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ticket_holds WHERE status = ?", Integer.class, status);
    }

    public int countOrders() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    public int countPaymentTransactions() {
        return jdbc.queryForObject("SELECT count(*) FROM payment_transactions", Integer.class);
    }

    public int countOutbox(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE status = ?", Integer.class, status);
    }

    /** Pushes a hold's expiry into the past so the sweeper will reclaim it on its next pass. */
    public void expireHold(String holdToken) {
        jdbc.update(
                "UPDATE ticket_holds SET expires_at = ? WHERE hold_token = ?",
                Timestamp.from(Instant.now().minusSeconds(30)),
                holdToken);
    }

    /**
     * The invariant the whole system protects:
     * {@code confirmed_sold + active_holds + remaining == total_capacity}.
     */
    public boolean stockInvariantHolds(long tierId) {
        Integer capacity = jdbc.queryForObject(
                "SELECT total_capacity FROM ticket_tiers WHERE id = ?", Integer.class, tierId);
        Integer sold = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(i.quantity), 0) FROM order_items i
                  JOIN orders o ON o.id = i.order_id
                 WHERE i.tier_id = ? AND o.status = 'CONFIRMED'
                """,
                Integer.class, tierId);
        Integer held = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM ticket_holds WHERE tier_id = ? AND status = 'ACTIVE'",
                Integer.class, tierId);

        return capacity != null && capacity == sold + held + remaining(tierId);
    }

    private long insertEvent(String title, Instant saleStart, Instant saleEnd) {
        return jdbc.queryForObject(
                """
                INSERT INTO events (title, description, venue_name, event_start_time,
                                    sale_start_time, sale_end_time, status, created_at, updated_at)
                VALUES (?, 'fixture', 'Test Arena', ?, ?, ?, 'PUBLISHED', now(), now())
                RETURNING id
                """,
                Long.class,
                title,
                Timestamp.from(Instant.now().plus(60, ChronoUnit.DAYS)),
                Timestamp.from(saleStart),
                Timestamp.from(saleEnd));
    }
}
