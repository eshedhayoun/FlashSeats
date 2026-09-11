package com.flashseats.catalog.facade;

/**
 * Where "now" sits relative to an event's sale window. <strong>Derived, never stored</strong> — a
 * stored copy would go stale the moment the clock moved past it.
 *
 * <p>Gates (ADR-016): joining the queue and creating a hold both require {@link #OPEN}; checkout
 * additionally allows {@link #CLOSED} within a grace period of {@code sale_end_time}, so a buyer who
 * reached the payment form in time is not cut off mid-transaction.
 */
public enum EventWindowStatus {

    /** Published, but the sale has not started. The landing page shows a countdown. */
    UPCOMING,

    /** Published and inside the sale window. The only state in which stock moves. */
    OPEN,

    /** Not published, or past {@code sale_end_time}. */
    CLOSED
}
