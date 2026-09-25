package com.flashseats.catalog.facade;

import java.util.List;
import java.util.Map;

/**
 * The only way into {@code catalog} from another module. Callers: {@code hold} (tier, price,
 * window, inventory movement), {@code queue} (window and remaining stock), {@code order} (pricing,
 * rebuild) and {@code saleflow}. {@code catalog} depends on nothing, so every edge is acyclic.
 *
 * <p>No method opens its own transaction (global standards §5). {@link #tryReserve} and
 * {@link #restore} are Redis writes and must be called <strong>outside</strong> one (ADR-046).
 */
public interface CatalogFacade {

    /**
     * Returned by {@link #getRemainingForEvent} when a tier has no counter at all.
     *
     * <p>Callers must treat this as a <strong>fault</strong>, never as zero: it means inventory state
     * is missing and needs rebuilding, not that the tier sold out (ADR-004).
     */
    int COUNTER_UNAVAILABLE = -1;

    /**
     * @throws com.flashseats.shared.error.FlashSeatsException {@code EVENT_NOT_FOUND} if the event
     *     does not exist, {@code TIER_NOT_FOUND} if the tier is not this event's — see
     *     {@link com.flashseats.catalog.exception.CatalogErrors}
     */
    TierSummary getTierSummary(long eventId, long tierId);

    EventSummary getEventSummary(long eventId);

    EventWindowStatus getWindowStatus(long eventId);

    /** Ids of events currently inside their sale window. Drives the promotion worker. */
    List<Long> findOpenEventIds();

    /**
     * Ids of events inside their sale window that are open <strong>or paused</strong>.
     *
     * <p>Not the same question as {@link #findOpenEventIds}, and the difference matters: a paused
     * sale admits nobody, but it is still an operator's responsibility and must still be watched.
     * This is what the {@code stock.drift} gauge iterates, because pausing a sale is exactly what
     * someone does while investigating a counter.
     */
    List<Long> findManagedEventIds();

    /**
     * Total remaining across every tier of an event. Bounds how many buyers the queue admits.
     *
     * @return remaining seats, or {@link #COUNTER_UNAVAILABLE} when <em>any</em> tier has no counter:
     *     a <strong>fault</strong>, never a sold-out sale (ADR-035)
     */
    int getRemainingForEvent(long eventId);

    /**
     * Public availability buckets for every tier in the event.
     *
     * <p>Used by the waiting-room stream to push {@code tier-availability} frames without copying
     * bucket rules out of {@code catalog} (ADR-027).
     */
    List<TierAvailability> getTierAvailability(long eventId);

    /**
     * Atomically takes seats from a tier. <strong>Must not be called inside a SQL transaction</strong>:
     * it is a Redis write that cannot roll back (ADR-023, ADR-046). The caller writes the hold that
     * justifies it immediately afterwards, and {@link #restore}s only on a definite rejection.
     */
    ReserveResult tryReserve(long eventId, long tierId, int quantity);

    /**
     * Returns seats to a tier.
     *
     * <p>Call only after winning the settle-once claim on the hold — that claim, not this call, is
     * what makes restoration exactly-once (ADR-019) — and only <strong>after the claim has
     * committed</strong>. Incrementing before the commit would hand the seats back and then let the
     * transaction put the hold back to {@code ACTIVE}, which is an oversell.
     *
     * <p>Does nothing if the tier has no counter: creating one here would conjure inventory out of a
     * single expiring hold (ADR-004). Those seats are reported by the drift gauge and returned by a
     * rebuild.
     */
    void restore(long eventId, long tierId, int quantity);

    /**
     * Every tier of an event and the capacity it was created with.
     *
     * <p>The starting point for a rebuild: {@code capacity − confirmed − held} is the only legal way
     * to work out what a lost counter should hold (ADR-004).
     */
    Map<Long, Integer> getTierCapacities(long eventId);

    /**
     * The live counters as they stand, tier id to remaining.
     *
     * <p><strong>A tier with no counter is absent, never zero.</strong> The caller is either
     * measuring drift or repairing a fault, and both need to tell "nothing left" from "nothing
     * known" (ADR-035, ADR-040).
     */
    Map<Long, Integer> getLiveCounters(long eventId);

    /**
     * Overwrites the live counters with values derived from the ledger.
     *
     * <p><strong>The only legal reseed of an open sale.</strong> Every other path into a counter
     * either creates it from capacity before the sale opens (pre-warm) or moves it by one
     * reservation. Passing anything not computed as {@code capacity − confirmed − held} would
     * resurrect sold tickets, which is the failure ADR-004 exists to prevent.
     *
     * <p>Callers must hold the rebuild lock. This method does not take it, because the lock has to
     * span the ledger read that produced these numbers — and that read happens in the caller.
     */
    void applyRebuild(long eventId, Map<Long, Integer> remainingByTier);
}
