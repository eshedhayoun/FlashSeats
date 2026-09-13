package com.flashseats.payment.model;

/**
 * Gateway transaction lifecycle.
 *
 * <pre>
 *   INITIATED ──► PROCESSING ──► SUCCEEDED ──► REFUNDED
 *                      └───────► FAILED
 * </pre>
 *
 * <p>{@code PROCESSING} is the 3-D Secure parking spot: the buyer has been sent away to
 * authenticate, and the row must stay findable so a resumed checkout re-reads <em>that</em> intent
 * rather than opening a second one
 * ({@code findFirstByHoldTokenAndStatusOrderByIdDesc}).
 *
 * <p>Internal to this module. {@code order} reads outcomes from
 * {@link com.flashseats.payment.facade.PaymentResult} instead, so no caller has to reason about the
 * gateway's state machine.
 */
public enum PaymentStatus {
    INITIATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    REFUNDED
}
