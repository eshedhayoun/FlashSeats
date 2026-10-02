package com.flashseats.catalog.facade;

/**
 * Where "now" sits relative to an event's sale window. <strong>Derived, never stored</strong> — a
 * stored copy would go stale the moment the clock moved past it.
 *
 * <p>Gates (ADR-016, ADR-066): creating a hold requires {@link #OPEN}. Joining the queue allows
 * {@link #PAUSED} as well, because a line that keeps forming during a pause is still in arrival order
 * when it resumes. Checkout allows {@code OPEN}, {@code PAUSED}, and {@link #CLOSED} within a grace
 * period of {@code sale_end_time}, so a buyer who already holds seats is never cut off mid-payment.
 * <strong>Every gate tests for the values it admits</strong>, never for the ones it refuses, so a gate
 * that has not heard of a status refuses it.
 */
public enum EventWindowStatus {

    /** Published, but the sale has not started. The landing page shows a countdown. */
    UPCOMING,

    /** Published and inside the sale window. The only state in which stock moves. */
    OPEN,

    /**
     * Inside the sale window, halted by an operator, and resumable (ADR-066). Nobody is promoted and no
     * hold is created, but nothing is torn down: the line, passes, admissions, holds and stock all stay
     * where they are, and buyers are told the sale is paused rather than over.
     */
    PAUSED,

    /** Not published, or past {@code sale_end_time}. */
    CLOSED
}
