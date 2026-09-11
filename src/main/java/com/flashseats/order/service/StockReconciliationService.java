package com.flashseats.order.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.order.config.OrderProperties;
import com.flashseats.order.exception.StockRebuildInProgressException;
import com.flashseats.order.repository.OrderRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger's opinion of how many seats are left, and the repair when the live counter disagrees.
 *
 * <p><strong>Why this lives in {@code order}.</strong> The invariant spans three modules'
 * tables — {@code ticket_tiers}, {@code order_items}, {@code ticket_holds} — and {@code order} is
 * the only module that can legally reach all three: it owns the orders, and {@code order → hold} and
 * {@code order → catalog} are existing facade edges. Putting it in {@code catalog} would give
 * {@code catalog} its first outbound dependency and make the graph cyclic; reading the other
 * modules' tables with one native query would hide the same violation somewhere
 * {@code ApplicationModules.verify()} cannot see it.
 *
 * <p>Redis is the live counter and PostgreSQL is the ledger. Everything here compares the two.
 */
@Slf4j
@Service
public class StockReconciliationService {

    private final OrderRepository orders;
    private final HoldFacade holds;
    private final CatalogFacade catalog;
    private final OrderProperties properties;

    /** Takes the rebuild lock and reads the ledger. Default isolation; the lock does the work. */
    private final TransactionTemplate locked;

    /** Reads both ledger sums in one snapshot, so measurement cannot invent drift. */
    private final TransactionTemplate snapshot;

    /**
     * The largest absolute gap between any open tier's counter and its ledger.
     *
     * <p>Invariant 1, as a number. Non-zero means seats exist in one system and not the other.
     */
    private final AtomicInteger worstDrift = new AtomicInteger();

    /** Open tiers with no live counter at all — a different and louder fault than drift (ADR-004). */
    private final AtomicInteger countersMissing = new AtomicInteger();

    public StockReconciliationService(
            OrderRepository orders,
            HoldFacade holds,
            CatalogFacade catalog,
            OrderProperties properties,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters) {
        this.orders = orders;
        this.holds = holds;
        this.catalog = catalog;
        this.properties = properties;

        this.locked = new TransactionTemplate(transactionManager);
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);

        Gauge.builder("flashseats.stock.drift", worstDrift, AtomicInteger::get)
                .description("Largest |counter - ledger| across open tiers. Should always be zero.")
                .register(meters);
        Gauge.builder("flashseats.stock.counters.missing", countersMissing, AtomicInteger::get)
                .description("Open tiers with no live counter. Each one answers holds with a 503.")
                .register(meters);
    }

    // -------------------------------------------------------------------- drift

    /**
     * Recomputes both gauges.
     *
     * <p>Read-only and therefore safe on every replica at once — each simply reports its own
     * measurement, so no lock is needed.
     *
     * <p><strong>Alarm on sustained non-zero, not on a single sample.</strong> Redis and PostgreSQL
     * are not read in one snapshot, so a hold created between the two reads shows up as a
     * momentary gap. The two SQL sums <em>are</em> one snapshot, which removes the only source of
     * drift this process can manufacture by itself.
     */
    @Scheduled(
            fixedDelayString = "${flashseats.order.drift-interval-ms}",
            initialDelayString = "${flashseats.order.drift-interval-ms}")
    public void measureDrift() {
        int worst = 0;
        int missing = 0;

        // MANAGED, not open: a paused sale is still measured. Pausing is what an operator does
        // *while* investigating a counter, so losing the drift gauge at that exact moment would
        // take the instrument away from the person using it.
        for (long eventId : catalog.findManagedEventIds()) {
            Map<Long, Integer> counters = catalog.getLiveCounters(eventId);
            for (Map.Entry<Long, Integer> expected : ledgerSnapshot(eventId).entrySet()) {
                Integer live = counters.get(expected.getKey());
                if (live == null) {
                    log.error(
                            "Tier {} of event {} has no live counter; the ledger says {} seats remain."
                                    + " Holds will answer 503 until a rebuild runs (ADR-004)",
                            expected.getKey(),
                            eventId,
                            expected.getValue());
                    missing++;
                    continue;
                }
                int drift = live - expected.getValue();
                if (drift != 0) {
                    log.error(
                            "Stock drift on tier {} of event {}: counter says {}, ledger says {}",
                            expected.getKey(),
                            eventId,
                            live,
                            expected.getValue());
                }
                worst = Math.max(worst, Math.abs(drift));
            }
        }

        worstDrift.set(worst);
        countersMissing.set(missing);
    }

    // ------------------------------------------------------------------ rebuild

    /**
     * ADR-004's only legal recovery from a lost or wrong counter.
     *
     * <p>Takes two ledger snapshots a settling window apart and writes the <strong>smaller</strong>
     * of the two. That is what makes the procedure safe to run on a live sale: a reserve decrements
     * Redis just before its hold row commits, so a lone snapshot can miss a hold that is about to
     * exist and write a count that is too high — an oversell created by the repair itself. By the
     * second read that hold has landed. Seats genuinely abandoned — decremented by a replica that
     * then died — read identically both times and are correctly returned.
     *
     * <p>Taking the minimum also means any hold created <em>during</em> the window is subtracted,
     * which errs toward under-counting. That is the direction this whole design errs in: invisible
     * seats are lost revenue that the next rebuild recovers, while phantom seats are an oversell
     * that nothing recovers.
     *
     * @throws StockRebuildInProgressException if another rebuild holds this event's lock
     */
    public Map<Long, Integer> rebuild(long eventId) {
        Map<Long, Integer> first = lockedLedgerSnapshot(eventId);
        settle();
        Map<Long, Integer> second = lockedLedgerSnapshot(eventId);

        Map<Long, Integer> conservative = new HashMap<>();
        first.forEach((tierId, remaining) ->
                conservative.put(tierId, Math.min(remaining, second.getOrDefault(tierId, remaining))));

        catalog.applyRebuild(eventId, conservative);
        return conservative;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * One tier-by-tier reading of {@code capacity − confirmed − held}, under the rebuild lock.
     *
     * <p>The lock is transaction-scoped, so it is retaken for each snapshot rather than held across
     * the settling window — sleeping inside a transaction is exactly what ADR-023 forbids. A second
     * rebuild arriving between the two therefore loses the race here instead of silently
     * interleaving with this one.
     */
    private Map<Long, Integer> lockedLedgerSnapshot(long eventId) {
        return locked.execute(status -> {
            if (!orders.tryStockRebuildLock(eventId)) {
                throw new StockRebuildInProgressException(eventId);
            }
            return remainingByTier(eventId);
        });
    }

    /**
     * The same computation without the lock, for measurement rather than repair.
     *
     * <p>{@code REPEATABLE_READ} so both sums see one instant. Under {@code READ COMMITTED} each
     * statement takes its own snapshot, and a checkout committing between them moves a hold out of
     * {@code ACTIVE} and into {@code CONFIRMED} without either query seeing it — inventing drift
     * that does not exist.
     */
    private Map<Long, Integer> ledgerSnapshot(long eventId) {
        return snapshot.execute(status -> remainingByTier(eventId));
    }

    private Map<Long, Integer> remainingByTier(long eventId) {
        Map<Long, Integer> remaining = new HashMap<>();
        catalog.getTierCapacities(eventId)
                .forEach((tierId, capacity) -> remaining.put(
                        tierId,
                        capacity
                                - orders.sumConfirmedQuantityForTier(tierId)
                                - holds.sumActiveQuantityForTier(tierId)));
        return remaining;
    }

    /** Long enough for any reserve already in Redis to have committed its hold row. */
    private void settle() {
        try {
            Thread.sleep(properties.getRebuildSettleMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rebuild interrupted while settling", interrupted);
        }
    }
}
