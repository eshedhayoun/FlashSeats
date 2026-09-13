package com.flashseats.payment.model;

/**
 * Gateway transaction lifecycle.
 *
 * <pre>
 *   INITIATED ──► SUCCEEDED ──► REFUNDED
 *        └──────► FAILED
 * </pre>
 *
 * <p>There is no {@code PROCESSING}. One was declared and never assigned: the gateway call sits
 * between two short transactions, so a row is {@code INITIATED} for exactly as long as the provider
 * takes to answer and there is no third moment to name.
 *
 * <p>Internal to this module. {@code order} reads outcomes from
 * {@link com.flashseats.payment.facade.PaymentResult} instead, so no caller has to reason about the
 * gateway's state machine.
 */
public enum PaymentStatus {
    INITIATED,
    SUCCEEDED,
    FAILED,
    REFUNDED
}
