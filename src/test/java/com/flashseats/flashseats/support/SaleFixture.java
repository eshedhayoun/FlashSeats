package com.flashseats.flashseats.support;

import com.flashseats.shared.cache.DerivedStateCache;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
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
    private final List<DerivedStateCache> caches;

    public SaleFixture(
            JdbcTemplate jdbc, StringRedisTemplate redis, List<DerivedStateCache> caches) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.caches = caches;
    }

    /**
     * Wipes every table <strong>and Redis</strong>, so each test starts from a known, empty world.
     *
     * <p>Both halves are needed. {@code RESTART IDENTITY} makes the next test's event id {@code 1}
     * again, while the containers are static and shared across every test class — so without the
     * flush, one class's {@code queue:waiting:1} is the next class's starting queue, and a
     * {@code payment:inflight:} key outlives the hold it belonged to. Nothing about that fails
     * immediately; it surfaces later as a test that passes alone and fails in a suite.
     *
     * <p>There is now a third half, for the same reason: in-process caches of rows this truncates
     * (ADR-051). An id that comes back as {@code 1} finds a cached snapshot of the previous test's
     * sale, so every {@link DerivedStateCache} is cleared here — which is what lets the suite run
     * with the metadata cache <em>enabled</em>, exercising the configuration production uses.
     */
    public void reset() {
        jdbc.execute(
                """
                TRUNCATE bot_audit_logs, ip_rules, notification_logs, webhook_events,
                         payment_transactions, outbox_events, order_items, orders, ticket_holds,
                         ticket_tiers, events
                RESTART IDENTITY CASCADE
                """);

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        caches.forEach(DerivedStateCache::invalidateAll);
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

        setStockCounter(eventId, tierId, capacity);
        caches.forEach(DerivedStateCache::invalidateAll);
        return tierId;
    }

    /** A tier deliberately left with no counter, to exercise the unreadable-inventory fault path. */
    public long tierWithoutCounter(long eventId, String name, long priceCents, int capacity) {
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

        caches.forEach(DerivedStateCache::invalidateAll);
        return tierId;
    }

    /**
     * Takes a tier's counter to zero — genuinely sold out, as distinct from having no counter.
     *
     * <p>The two are one row apart and must never read the same to a buyer (ADR-040).
     */
    public void drainTier(long tierId) {
        setStockCounter(eventIdOf(tierId), tierId, 0);
    }

    /**
     * Ends a sale's window now, as the clock would.
     *
     * <p>Written with SQL, so no application write evicts the cached snapshot of the row — the
     * caches are cleared here explicitly rather than waiting out a TTL a test is faster than.
     */
    public void closeSale(long eventId) {
        jdbc.update(
                "UPDATE events SET sale_end_time = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                eventId);
        caches.forEach(DerivedStateCache::invalidateAll);
    }

    /**
     * Forces the live counter to a value, as a Redis restart or a lost restore would leave it.
     *
     * <p>The only way to stage a counter that disagrees with the ledger: every legitimate path moves
     * it by exactly one reservation, so drift cannot be produced through the API at all.
     *
     * <p>Every key here is spelled out rather than built with catalog's own helper, for the same
     * reason the rows above are raw SQL: a fixture that shares a bug with the code it verifies
     * proves nothing.
     */
    public void setStockCounter(long eventId, long tierId, int remaining) {
        redis.opsForValue().set("catalog:stock:" + eventId + ":" + tierId, String.valueOf(remaining));
    }

    /**
     * Loses a tier's live counter, as an eviction, a {@code FLUSHDB} or a cold Redis would.
     *
     * <p>Distinct from {@link #drainTier}: that sells a tier out, this makes the system unable to
     * say. The two are one key apart and must never read the same to a buyer (ADR-004, ADR-040).
     */
    public void loseStockCounter(long eventId, long tierId) {
        redis.delete("catalog:stock:" + eventId + ":" + tierId);
    }

    /**
     * Makes an event's counters look like a <em>different</em> Redis instance vouched for them.
     *
     * <p>Equivalent to restarting the server, without restarting a container the whole suite shares.
     * What the guard compares is this stored {@code run_id} against the live one, and it cannot tell
     * which of the two moved.
     */
    public void forgeEarlierRedisInstance(long eventId) {
        redis.opsForValue().set("catalog:vouch:" + eventId, "a-previous-redis-incarnation");
    }

    /** The live stock counter as Redis holds it, or {@code -1} when the key does not exist. */
    public int stockCounter(long eventId, long tierId) {
        String value = redis.opsForValue().get("catalog:stock:" + eventId + ":" + tierId);
        return value == null ? -1 : Integer.parseInt(value);
    }

    /**
     * Seats the system believes are on sale — <strong>the live Redis counter</strong>, not the row.
     *
     * <p>The tier's event is looked up rather than passed in, so the two dozen assertions that
     * already say {@code remaining(tierId)} keep meaning what they meant when the counter was a
     * column.
     */
    public int remaining(long tierId) {
        return stockCounter(eventIdOf(tierId), tierId);
    }

    private long eventIdOf(long tierId) {
        return jdbc.queryForObject(
                "SELECT event_id FROM ticket_tiers WHERE id = ?", Long.class, tierId);
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
    //overloaded method to count outbox events by event type and status
    public int countOutbox(String eventType, String status) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM outbox_events
                WHERE event_type = ?
                AND status = ?
                """,
                Integer.class,
                eventType,
                status);
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

    /** The order number written against a hold, whoever wrote it. */
    public String orderNumberFor(String holdToken) {
        return jdbc.queryForObject(
                "SELECT order_number FROM orders WHERE hold_token = ?", String.class, holdToken);
    }

    /** Backdates an order so the staleness rule sees it as stranded rather than in flight. */
    public void ageOrder(String holdToken, java.time.Duration by) {
        jdbc.update(
                "UPDATE orders SET updated_at = ?, created_at = ? WHERE hold_token = ?",
                Timestamp.from(Instant.now().minus(by)),
                Timestamp.from(Instant.now().minus(by)),
                holdToken);
    }
    public void ageHold(String holdToken, Duration age, Duration remaining) {
        Instant createdAt = Instant.now().minus(age);
        Instant expiresAt = Instant.now().plus(remaining);

        jdbc.update(
                "UPDATE ticket_holds SET created_at = ?, expires_at = ? WHERE hold_token = ?",
                Timestamp.from(createdAt),
                Timestamp.from(expiresAt),
                holdToken);

        caches.forEach(DerivedStateCache::invalidateAll);
    }

    /** Pushes a hold's expiry into the past so the sweeper will reclaim it on its next pass. */
    public void expireHold(String holdToken) {
        jdbc.update(
                "UPDATE ticket_holds SET expires_at = ? WHERE hold_token = ?",
                Timestamp.from(Instant.now().minusSeconds(30)),
                holdToken);
    }

    /**
     * Runs {@code body} with a tier's row temporarily gone, then puts it back exactly as it was.
     *
     * <p>Nothing references {@code ticket_tiers} from {@code ticket_holds}, so this leaves a live
     * hold pointing at a tier the catalog cannot describe — which is how a settlement can be made to
     * fail for a reason that is <strong>not</strong> one of the three definite hold outcomes. Every
     * other failure reachable on that path is definite, which is the point: the branch that must not
     * refund cannot be reached any other way.
     *
     * <p>The caches are cleared on both edges. A raw delete evicts nothing by itself (ADR-051).
     */
    public void withTierRemoved(long tierId, Runnable body) {
        var row = jdbc.queryForMap("SELECT * FROM ticket_tiers WHERE id = ?", tierId);
        jdbc.update("DELETE FROM ticket_tiers WHERE id = ?", tierId);
        caches.forEach(DerivedStateCache::invalidateAll);
        try {
            body.run();
        } finally {
            jdbc.update(
                    """
                    INSERT INTO ticket_tiers
                           (id, event_id, tier_name, price_cents, currency, total_capacity,
                            max_per_order, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())
                    """,
                    row.get("id"), row.get("event_id"), row.get("tier_name"), row.get("price_cents"),
                    row.get("currency"), row.get("total_capacity"), row.get("max_per_order"));
            caches.forEach(DerivedStateCache::invalidateAll);
        }
    }

    /** Every address in the bot audit trail — the RESOLVED one, not the socket peer. */
    public java.util.List<String> botAuditAddresses() {
        return jdbc.queryForList("SELECT ip_address FROM bot_audit_logs", String.class);
    }

    /**
     * Every outcome in the bot audit trail.
     *
     * <p>Deliberately returns them all rather than a count: the assertion worth making is that no
     * row describes an <em>allowed</em> request. A count cannot say that, and a write per request
     * during a flash sale is the failure this table's shape exists to avoid.
     */
    public java.util.List<String> botAuditOutcomes() {
        return jdbc.queryForList("SELECT outcome FROM bot_audit_logs", String.class);
    }

    /** The order's own status, read straight from the row rather than through an API that filters. */
    public String orderStatus(String holdToken) {
        return jdbc.queryForObject(
                "SELECT status FROM orders WHERE hold_token = ?", String.class, holdToken);
    }

    /**
     * How many times <em>this</em> delivery was claimed. A replay must not add one.
     *
     * <p>Scoped to the event id rather than counting the table, deliberately: a global count makes an
     * assertion about every other test that has ever run, so it reports someone else's leftover row
     * as this test's failure. The claim is per delivery, and so is the question worth asking.
     */
    public int countWebhookEvents(String stripeEventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM webhook_events WHERE stripe_event_id = ?",
                Integer.class,
                stripeEventId);
    }

    /**
     * Whether a claimed delivery was seen through to the end.
     *
     * <p>{@code processed_at IS NULL} means in flight, never failed — a failed settlement deletes
     * its row so the provider's redelivery finds a clean claim (ADR-038).
     */
    public boolean webhookProcessed(String eventId) {
        Integer processed = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_events WHERE stripe_event_id = ? AND processed_at IS NOT NULL",
                Integer.class,
                eventId);
        return processed != null && processed == 1;
    }

    /** The provider intent recorded against a hold's charge, or null if nothing was recorded. */
    public String gatewayReferenceFor(String holdToken) {
        return jdbc.query(
                "SELECT stripe_payment_intent_id FROM payment_transactions WHERE hold_token = ?"
                        + " ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null,
                holdToken);
    }

    /** How many of the buyer's three card attempts an order has spent. */
    public int paymentAttemptsFor(String holdToken) {
        return jdbc.queryForObject(
                "SELECT payment_attempts FROM orders WHERE hold_token = ?", Integer.class, holdToken);
    }

    /** The ledger status of a hold's most recent charge attempt. */
    public String paymentStatusFor(String holdToken) {
        return jdbc.query(
                "SELECT status FROM payment_transactions WHERE hold_token = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null,
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
