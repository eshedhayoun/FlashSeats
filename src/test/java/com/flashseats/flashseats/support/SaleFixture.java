package com.flashseats.flashseats.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final StringRedisTemplate redis;

    public SaleFixture(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    /**
     * Wipes every table <strong>and Redis</strong>, so each test starts from a known, empty world.
     *
     * <p>Both halves are needed. {@code RESTART IDENTITY} makes the next test's event id {@code 1}
     * again, while the containers are static and shared across every test class — so without the
     * flush, one class's {@code queue:waiting:1} is the next class's starting queue, and a
     * {@code payment:inflight:} key outlives the hold it belonged to. Nothing about that fails
     * immediately; it surfaces later as a test that passes alone and fails in a suite.
     */
    public void reset() {
        jdbc.execute(
                """
                TRUNCATE notification_logs, payment_transactions, outbox_events, order_items,
                         orders, ticket_holds, tier_inventory, ticket_tiers, events
                RESTART IDENTITY CASCADE
                """);

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
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

    /**
     * Takes a tier's counter to zero — genuinely sold out, as distinct from having no counter.
     *
     * <p>The two are one row apart and must never read the same to a buyer (ADR-040).
     */
    public void drainTier(long tierId) {
        jdbc.update("UPDATE tier_inventory SET remaining = 0 WHERE tier_id = ?", tierId);
    }

    /** Ends a sale's window now, as the clock would. */
    public void closeSale(long eventId) {
        jdbc.update(
                "UPDATE events SET sale_end_time = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                eventId);
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

    /**
     * Leaves an order row {@code PENDING} for a hold, as a process killed mid-checkout would.
     *
     * <p>Written directly because the situation cannot be produced through the API: every code path
     * that reaches {@code PENDING} also resolves it. Only a crash between the commit and the charge
     * leaves this behind, and that is precisely the state ADR-034's staleness rule exists for.
     */
    public void strandPendingOrder(String holdToken) {
        jdbc.update(
                """
                INSERT INTO orders (order_number, hold_token, user_session_id, user_email,
                                    receipt_token, event_id, total_amount_cents, currency, status,
                                    payment_attempts, created_at, updated_at)
                SELECT 'TK-STRANDED', ?, h.user_session_id, 'stranded@example.com', 'tok', h.event_id,
                       1, 'USD', 'PENDING', 0, now(), now()
                  FROM ticket_holds h
                 WHERE h.hold_token = ?
                """,
                holdToken,
                holdToken);
    }

    /** Backdates an order so the staleness rule sees it as stranded rather than in flight. */
    public void ageOrder(String holdToken, java.time.Duration by) {
        jdbc.update(
                "UPDATE orders SET updated_at = ?, created_at = ? WHERE hold_token = ?",
                Timestamp.from(Instant.now().minus(by)),
                Timestamp.from(Instant.now().minus(by)),
                holdToken);
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
