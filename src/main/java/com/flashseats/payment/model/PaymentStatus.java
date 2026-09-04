package com.flashseats.payment.model;

/**
 * Gateway transaction lifecycle.
 *
 * <pre>
 *   INITIATED ──► PROCESSING ──► SUCCEEDED ──► REFUNDED
 *                      └───────► FAILED
 * </pre>
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
