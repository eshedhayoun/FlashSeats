package com.flashseats.catalog.facade;

import java.util.List;

/**
 * The only legal way into {@code catalog} from another module.
 *
 * <p>Callers: {@code hold} (tier validity, price, window, and the inventory movement),
 * {@code queue} (window gate and remaining stock — ADR-031), {@code order} (server-side pricing),
 * {@code saleflow} (rehydration). {@code catalog} itself depends on nothing, so every one of those
 * edges is acyclic.
 *
 * <p>Per global standards §5 no method here opens a transaction of its own; the two mutating methods
 * <em>require</em> one, so the caller's boundary governs.
 */
public interface CatalogFacade {

    /**
     * Returned by {@link #getRemaining} when the tier has no counter at all.
     *
     * <p>Callers must treat this as a <strong>fault</strong>, never as zero: it means inventory state
     * is missing and needs rebuilding, not that the tier sold out (ADR-004).
     */
    int COUNTER_UNAVAILABLE = -1;

    /**
     * @throws com.flashseats.catalog.exception.EventNotFoundException if the event does not exist
     * @throws com.flashseats.catalog.exception.TierNotFoundException if the tier is not this event's
     */
    TierSummary getTierSummary(long eventId, long tierId);

    EventSummary getEventSummary(long eventId);

    EventWindowStatus getWindowStatus(long eventId);

    /** Ids of events currently inside their sale window. Drives the promotion worker. */
    List<Long> findOpenEventIds();

    /**
     * Exact remaining seats for one tier, for internal use only — never rendered to a buyer, who
     * sees a bucket instead (ADR-027).
     *
     * @return remaining seats, or {@code -1} when no counter exists. {@code -1} is a
     *     <strong>fault</strong> and callers must treat it as one: it means inventory state is
     *     missing, not that the tier is sold out (ADR-004).
     */
    int getRemaining(long tierId);

    /**
     * Total remaining across every tier of an event. Bounds how many buyers the queue admits.
     *
     * @return remaining seats, or {@link #COUNTER_UNAVAILABLE} when <em>any</em> tier of the event
     *     has no counter. As with {@link #getRemaining}, that is a <strong>fault</strong> and must
     *     never be read as a sold-out sale: doing so drained an entire waiting room in the first
     *     pass, because a {@code SUM} over missing rows is indistinguishable from zero (ADR-035).
     */
    int getRemainingForEvent(long eventId);

    /**
     * Atomically takes seats from a tier. <strong>Must be called inside the caller's
     * transaction</strong> so the reservation that justifies the decrement commits or rolls back
     * with it.
     *
     * @return true when reserved, false when stock is insufficient
     */
    boolean tryReserve(long tierId, int quantity);

    /**
     * Returns seats to a tier. Call only after winning the settle-once claim on the hold — that
     * claim is what makes restoration exactly-once (ADR-019).
     */
    void restore(long tierId, int quantity);
}
